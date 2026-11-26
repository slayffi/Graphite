package com.graphite.torrent.desktop

import com.graphite.torrent.core.net.BoundedIo
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Makes sure only one Graphite runs. A second launch (double-click on a .torrent, a magnet link from the
 * browser) forwards its arguments to the running instance over a loopback socket and exits.
 */
class SingleInstance(private val dir: File) {
    private var lock: FileLock? = null
    private var channel: FileChannel? = null
    private var server: ServerSocket? = null

    /** Returns true when this process is the primary instance. */
    fun acquire(): Boolean {
        dir.mkdirs()
        val file = RandomAccessFile(File(dir, "instance.lock"), "rw")
        channel = file.channel
        lock = runCatching { channel!!.tryLock() }.getOrNull()
        return lock != null
    }

    /**
     * Starts listening for arguments from later launches. The port and a random token are written to a file
     * in the user's own data folder, so only this user's processes can talk to the running instance.
     */
    fun listen(onArgs: (List<String>) -> Unit) {
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = socket
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        File(dir, "instance.port").writeText("${socket.localPort}\n$token")
        val expected = "$MAGIC $token".toByteArray()
        thread(name = "graphite-ipc", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                runCatching {
                    client.use { c ->
                        c.soTimeout = 3000
                        // Bounded in size and time: a stuck or endless sender must not block later launches.
                        val text = BoundedIo.read(c.getInputStream(), MAX_MESSAGE_BYTES, System.currentTimeMillis() + 3000)
                            .toString(Charsets.UTF_8)
                        val lines = text.lines().filter { it.isNotEmpty() }.take(MAX_ARGUMENTS + 1)
                        val first = lines.firstOrNull()?.toByteArray() ?: return@use
                        if (MessageDigest.isEqual(first, expected)) onArgs(lines.drop(1))
                    }
                }
            }
        }
    }

    /** Sends arguments to the primary instance. Returns false when it could not be reached. */
    fun forward(args: List<String>): Boolean = runCatching {
        val (port, token) = File(dir, "instance.port").readText().trim().lines().let { it[0].toInt() to it.getOrElse(1) { "" } }
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.getOutputStream().bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("$MAGIC $token")
                w.newLine()
                args.take(MAX_ARGUMENTS).forEach { w.write(it); w.newLine() }
            }
        }
        true
    }.getOrDefault(false)

    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }

    private companion object {
        const val MAGIC = "GRAPHITE-IPC-2"
        const val MAX_MESSAGE_BYTES = 256 * 1024
        const val MAX_ARGUMENTS = 64
    }
}
