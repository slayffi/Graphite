package com.graphite.torrent.data

import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.net.BoundedIo
import com.graphite.torrent.core.net.SafeHttp
import com.graphite.torrent.core.settings.WatchFolderAction
import com.graphite.torrent.data.automation.FileWatchFolderSource
import com.graphite.torrent.data.automation.WatchedFile
import com.graphite.torrent.data.net.MiniHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Everything reachable from outside is bounded: sizes, time, connections and where requests may go. */
class HardeningTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = createTempDirectory("hardening").toFile()
    private val loopback = InetAddress.getLoopbackAddress()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun get(port: Int, path: String = "/"): String = Socket(loopback, port).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    /** True when the server closed the connection without answering. */
    private fun closedWithoutAnswer(socket: Socket): Boolean = try {
        socket.soTimeout = 5000
        socket.getInputStream().read() < 0
    } catch (_: SocketException) {
        true
    }

    @Test
    fun hugeAnnouncedBodyIsRefusedBeforeAnyMemoryIsTaken() {
        val server = MiniHttpServer(loopback, 0, scope, maxBody = 1024) { _, res -> res.text(200, "OK", "fine") }
        val port = server.start()
        Socket(loopback, port).use { s ->
            s.getOutputStream().write("POST /rpc HTTP/1.1\r\nContent-Length: 2147483647\r\n\r\n".toByteArray())
            assertTrue(closedWithoutAnswer(s))
        }
        Socket(loopback, port).use { s ->
            s.getOutputStream().write("POST /rpc HTTP/1.1\r\nContent-Length: -5\r\n\r\n".toByteArray())
            assertTrue(closedWithoutAnswer(s))
        }
        assertTrue(get(port).endsWith("fine"), "the server keeps working")
        server.stop()
    }

    @Test
    fun stalledClientsCannotUseUpTheServer() {
        val server = MiniHttpServer(loopback, 0, scope, maxConnections = 3) { _, res -> res.text(200, "OK", "fine") }
        val port = server.start()
        // Three clients connect and send nothing: all slots are taken.
        val stalled = List(3) { Socket(loopback, port) }
        Thread.sleep(300)
        Socket(loopback, port).use { extra -> assertTrue(closedWithoutAnswer(extra), "beyond the limit connections are dropped at once") }
        stalled.forEach { it.close() }
        Thread.sleep(300)
        assertTrue(get(port).endsWith("fine"), "slots are free again")
        server.stop()
    }

    @Test
    fun peersOutsideTheAllowedNetworkAreDroppedUnread() {
        var handled = 0
        val server = MiniHttpServer(loopback, 0, scope, acceptPeer = { false }) { _, res -> handled++; res.text(200, "OK", "fine") }
        val port = server.start()
        Socket(loopback, port).use { s ->
            runCatching { s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray()) }
            assertTrue(closedWithoutAnswer(s))
        }
        assertEquals(0, handled)
        server.stop()
        assertTrue(MiniHttpServer.isLocalNetwork(InetAddress.getByName("192.168.1.20")))
        assertTrue(MiniHttpServer.isLocalNetwork(InetAddress.getByName("fd12:3456::1")))
        assertFalse(MiniHttpServer.isLocalNetwork(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun boundedReadsStopAtTheLimit() {
        assertEquals(10, BoundedIo.read(ByteArrayInputStream(ByteArray(10)), 10).size)
        assertFailsWith<IOException> { BoundedIo.read(ByteArrayInputStream(ByteArray(11)), 10) }
        // An endless stream is cut off instead of filling the memory.
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        assertFailsWith<IOException> { BoundedIo.read(endless, 1 shl 20) }
    }

    @Test
    fun httpDownloadsAreCappedAndDoNotReachIntoTheLocalNetwork() {
        val server = MiniHttpServer(loopback, 0, scope) { req, res ->
            when (req.path) {
                "/big" -> res.bytes(200, ByteArray(200_000), "application/octet-stream")
                "/redirect" -> res.head(302, "Found", mapOf("Location" to "/small", "Content-Length" to "0")).also { res.flush() }
                else -> res.text(200, "OK", "small")
            }
        }
        val port = server.start()
        val base = "http://127.0.0.1:$port"
        // A link written by a public feed must not make the app talk to this computer or the LAN.
        assertFailsWith<IOException> { SafeHttp.get("$base/small", 1 shl 20) }
        assertEquals("small", SafeHttp.get("$base/small", 1 shl 20, allowPrivateHosts = true).toString(Charsets.UTF_8))
        assertEquals("small", SafeHttp.get("$base/redirect", 1 shl 20, allowPrivateHosts = true).toString(Charsets.UTF_8))
        assertFailsWith<IOException> { SafeHttp.get("$base/big", 100_000, allowPrivateHosts = true) }
        assertFailsWith<IOException> { SafeHttp.get("file:///etc/hostname", 1 shl 20, allowPrivateHosts = true) }
        server.stop()
        assertTrue(SafeHttp.isPrivateHost("127.0.0.1") && SafeHttp.isPrivateHost("192.168.0.1") && SafeHttp.isPrivateHost("10.1.2.3"))
        assertFalse(SafeHttp.isPrivateHost("1.1.1.1"))
    }

    @Test
    fun oversizedFileInTheWatchedFolderIsMarkedInvalidNotLoaded() {
        val watched = File(dir, "watch").apply { mkdirs() }
        // 40 MB that exist only as a hole in the file: reading it would cost real memory.
        val big = File(watched, "huge.torrent")
        RandomAccessFile(big, "rw").use { it.setLength(40L * 1024 * 1024) }
        val source = FileWatchFolderSource()
        val entry = source.list(watched.absolutePath).single()
        assertFailsWith<IOException> { source.read(entry) }
        source.finish(WatchedFile(entry.name, entry.handle), WatchFolderAction.RENAME, success = false)
        assertTrue(File(watched, "huge.torrent.invalid").isFile && !big.exists(), "it is not picked up again on the next scan")
        assertEquals(EventType.WATCH_FOLDER_FAILED, EventType.valueOf("WATCH_FOLDER_FAILED"))
    }
}
