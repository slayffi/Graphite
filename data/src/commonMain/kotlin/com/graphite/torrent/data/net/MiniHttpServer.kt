package com.graphite.torrent.data.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteAddress: String,
)

/** Writes one HTTP/1.1 response; the connection is closed afterwards (no keep-alive). */
class HttpResponder(private val out: OutputStream) {
    private var headSent = false

    fun head(status: Int, reason: String, headers: Map<String, String>) {
        check(!headSent)
        headSent = true
        val sb = StringBuilder("HTTP/1.1 $status $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun body(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        out.write(bytes, offset, length)
    }

    fun flush() = out.flush()

    fun text(status: Int, reason: String, text: String, type: String = "text/plain; charset=utf-8") {
        val bytes = text.toByteArray(Charsets.UTF_8)
        head(status, reason, mapOf("Content-Type" to type, "Content-Length" to bytes.size.toString()))
        body(bytes)
        flush()
    }

    fun bytes(status: Int, bytes: ByteArray, type: String) {
        head(status, if (status == 200) "OK" else "Error", mapOf("Content-Type" to type, "Content-Length" to bytes.size.toString()))
        body(bytes)
        flush()
    }
}

/**
 * Tiny blocking HTTP/1.1 server used for local streaming and for the LAN remote-control channel.
 * Each connection is handled in its own coroutine on [Dispatchers.IO].
 */
class MiniHttpServer(
    private val bindAddress: InetAddress?,
    private val requestedPort: Int,
    private val scope: CoroutineScope,
    private val maxBody: Int = 16 * 1024 * 1024,
    private val handler: suspend (HttpRequest, HttpResponder) -> Unit,
) {
    private var socket: ServerSocket? = null
    private var job: Job? = null

    val port: Int get() = socket?.localPort ?: -1
    val isRunning: Boolean get() = socket?.isClosed == false

    @Synchronized
    fun start(): Int {
        socket?.let { if (!it.isClosed) return it.localPort }
        val server = ServerSocket(requestedPort, 64, bindAddress)
        socket = server
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val client = try {
                    runInterruptible { server.accept() }
                } catch (_: IOException) {
                    break
                }
                launch { serve(client) }
            }
        }
        return server.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        job?.cancel()
        socket = null
        job = null
    }

    private suspend fun serve(client: Socket) {
        client.use { s ->
            runCatching {
                s.soTimeout = 30_000
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream())
                val request = readRequest(input, s.inetAddress.hostAddress ?: "") ?: return
                handler(request, HttpResponder(s.getOutputStream()))
            }
        }
    }

    private fun readRequest(input: InputStream, remote: String): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            total += line.length
            if (total > 32 * 1024) return null
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > maxBody) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return HttpRequest(parts[0].uppercase(), parts[1], headers, body, remote)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }
}
