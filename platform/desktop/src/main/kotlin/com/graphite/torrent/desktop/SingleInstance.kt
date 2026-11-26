package com.graphite.torrent.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
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

    /** Starts listening for arguments from later launches. */
    fun listen(onArgs: (List<String>) -> Unit) {
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = socket
        File(dir, "instance.port").writeText(socket.localPort.toString())
        thread(name = "graphite-ipc", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                runCatching {
                    client.use { c ->
                        c.soTimeout = 3000
                        val lines = c.getInputStream().bufferedReader(Charsets.UTF_8).readLines()
                        if (lines.firstOrNull() == MAGIC) onArgs(lines.drop(1))
                    }
                }
            }
        }
    }

    /** Sends arguments to the primary instance. Returns false when it could not be reached. */
    fun forward(args: List<String>): Boolean = runCatching {
        val port = File(dir, "instance.port").readText().trim().toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.getOutputStream().bufferedWriter(Charsets.UTF_8).use { w ->
                w.write(MAGIC)
                w.newLine()
                args.forEach { w.write(it); w.newLine() }
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
        const val MAGIC = "GRAPHITE-IPC-1"
    }
}
