package com.graphite.torrent.data.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore

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
 *
 * Everything that happens before the handler runs is reachable without the key or token, so it is bounded:
 * a fixed number of connections with their own threads (a flood cannot starve the rest of the app), a
 * deadline for receiving the whole request, and bodies that are read in chunks up to [maxBody].
 */
class MiniHttpServer(
    private val bindAddress: InetAddress?,
    private val requestedPort: Int,
    private val scope: CoroutineScope,
    private val maxBody: Int = 4 * 1024 * 1024,
    private val maxConnections: Int = 16,
    /** Peers that may talk to this server at all; others are dropped before anything is read. */
    private val acceptPeer: (InetAddress) -> Boolean = { true },
    private val handler: suspend (HttpRequest, HttpResponder) -> Unit,
) {
    private var socket: ServerSocket? = null
    private var job: Job? = null
    private val slots = Semaphore(maxConnections)

    // Own threads: slow or stalled clients must not use up Dispatchers.IO, which the whole app shares.
    private val workers = Dispatchers.IO.limitedParallelism(maxConnections)

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
                    // A transient failure (out of file descriptors...) must not end the server for good.
                    if (server.isClosed) break
                    delay(200)
                    continue
                }
                if (!acceptPeer(client.inetAddress) || !slots.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                launch(workers) {
                    try {
                        serve(client)
                    } finally {
                        slots.release()
                    }
                }
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

    private suspend fun serve(client: Socket) = coroutineScope {
        client.use { s ->
            runCatching {
                s.soTimeout = READ_TIMEOUT_MS
                s.tcpNoDelay = true
                // The per-read timeout alone lets a client trickle one byte at a time forever.
                val deadline = launch {
                    delay(REQUEST_DEADLINE_MS)
                    runCatching { s.close() }
                }
                val request = try {
                    readRequest(BufferedInputStream(s.getInputStream()), s.inetAddress.hostAddress ?: "")
                } finally {
                    deadline.cancel()
                }
                if (request != null) handler(request, HttpResponder(s.getOutputStream()))
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
            if (total > MAX_HEADER_BYTES) return null
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > maxBody) return null
        // Memory is taken as the bytes arrive, not from the announced length.
        val body = ByteArrayOutputStream(minOf(length, CHUNK))
        val chunk = ByteArray(CHUNK)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (n < 0) return null
            body.write(chunk, 0, n)
            remaining -= n
        }
        return HttpRequest(parts[0].uppercase(), parts[1], headers, body.toByteArray(), remote)
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

    companion object {
        private const val READ_TIMEOUT_MS = 30_000
        private const val REQUEST_DEADLINE_MS = 15_000L
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val CHUNK = 64 * 1024

        /** Loopback and private networks only: the remote-control channel is meant for the home LAN. */
        fun isLocalNetwork(address: InetAddress): Boolean =
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                // IPv6 unique local addresses fc00::/7
                (address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC)
    }
}
