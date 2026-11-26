package com.graphite.torrent.data

import com.graphite.torrent.testing.FakeTorrentEngine
import com.graphite.torrent.core.engine.EngineConfig
import com.graphite.torrent.core.engine.FileResumeStore
import com.graphite.torrent.core.engine.LibtorrentEngine
import com.graphite.torrent.core.engine.NativeLibraries
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.AddSource
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.core.util.Base64Util
import com.graphite.torrent.data.db.GraphiteDatabase
import com.graphite.torrent.data.remote.Envelope
import com.graphite.torrent.data.remote.PairingCode
import com.graphite.torrent.data.remote.RemoteCrypto
import com.graphite.torrent.data.remote.RemoteServer
import com.graphite.torrent.data.remote.RemoteTorrentController
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.repo.HistoryRepository
import com.graphite.torrent.data.repo.StatsRepository
import com.graphite.torrent.data.repo.TorrentMetaRepository
import com.graphite.torrent.data.settings.SettingsRepository
import com.graphite.torrent.data.stream.ByteRange
import com.graphite.torrent.data.stream.RangeHeader
import com.graphite.torrent.data.stream.StreamServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteAndStreamingTest {
    private val dir = createTempDirectory("remote").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun rangeHeaders() {
        assertEquals(ByteRange(0, 99), RangeHeader.parse("bytes=0-99", 1000))
        assertEquals(ByteRange(500, 999), RangeHeader.parse("bytes=500-", 1000))
        assertEquals(ByteRange(900, 999), RangeHeader.parse("bytes=-100", 1000))
        assertEquals(ByteRange(10, 999), RangeHeader.parse("bytes=10-5000", 1000))
        assertNull(RangeHeader.parse("bytes=2000-", 1000))
        assertNull(RangeHeader.parse(null, 1000))
    }

    @Test
    fun cryptoRejectsTamperingAndForeignKeys() {
        val key = RemoteCrypto.newKey()
        val crypto = RemoteCrypto(key)
        val sealed = crypto.seal("hello".toByteArray())
        assertEquals("hello", crypto.open(sealed).toString(Charsets.UTF_8))
        val tampered = Envelope(sealed.n, sealed.c.dropLast(2) + if (sealed.c.endsWith("A")) "BB" else "AA")
        assertEquals(ErrorKind.REMOTE_AUTH, assertFailsWith<GraphiteException> { crypto.open(tampered) }.error.kind)
        assertEquals(ErrorKind.REMOTE_AUTH, assertFailsWith<GraphiteException> { RemoteCrypto(RemoteCrypto.newKey()).open(sealed) }.error.kind)
    }

    @Test
    fun pairingCodeRoundTrip() {
        val key = RemoteCrypto.newKey()
        val code = PairingCode.format("My PC", listOf("192.168.1.5", "10.0.0.2"), 48650, key)
        val pairing = assertNotNull(PairingCode.parse(code))
        assertEquals("My PC", pairing.name)
        assertEquals(listOf("192.168.1.5", "10.0.0.2"), pairing.hosts)
        assertEquals(48650, pairing.port)
        assertContentEquals(key, Base64Util.decodeUrl(pairing.keyBase64))
        assertNull(PairingCode.parse("https://example.org"))
        assertNull(PairingCode.parse("graphite://pair?v=1&n=x&p=1&h=a&k=short"))
    }

    @Test
    fun phoneControlsDesktopOverEncryptedChannel() = runBlocking {
        val db = GraphiteDatabase(DatabaseFactory.createInMemory())
        val engine = FakeTorrentEngine()
        val settings = SettingsRepository.create(File(dir, "s.json").absolutePath, scope)
        val port = ServerSocket(0).use { it.localPort }
        val log = EventLogRepository(db)
        val controller = LocalTorrentController(engine, settings, TorrentMetaRepository(db), StatsRepository(db), log, HistoryRepository(db), FileSystemStorage(dir.absolutePath), scope)
        controller.start()
        val server = RemoteServer(controller, settings, log, scope, deviceName = "Desk")
        settings.update { it.copy(remoteServerEnabled = true, remotePort = port) }
        server.bind()
        val payload = withTimeout(10_000) { server.pairingPayload.first { it != null } }!!
        val pairing = PairingCode.parse(payload)!!.copy(hosts = listOf("127.0.0.1"))

        val phone = RemoteTorrentController(pairing, scope)
        assertTrue(phone.ping())
        val id = phone.add(AddRequest(AddSource.Magnet("magnet:?xt=urn:btih:c9e15763f722f23e98a29decdfae341b98d53056&dn=Remote")))
        assertTrue(id in engine.state.value)
        phone.start()
        withTimeout(10_000) { phone.torrents.first { list -> list.any { it.id == id } } }
        phone.pause(listOf(id))
        assertEquals(TorrentState.PAUSED, engine.state.value.getValue(id).state)
        assertEquals("Desk", pairing.name)

        // A phone with another key is rejected.
        val stranger = RemoteTorrentController(pairing.copy(keyBase64 = Base64Util.encodeUrl(RemoteCrypto.newKey())), scope)
        assertEquals(ErrorKind.REMOTE_AUTH, assertFailsWith<GraphiteException> { stranger.pauseAll() }.error.kind)
        phone.stop()
        server.stop()
        controller.stop()
    }

    @Test
    fun streamsAFileWithRangeRequests() = runBlocking {
        NativeLibraries.ensureLoaded(null)
        val payloadDir = File(dir, "payload").apply { mkdirs() }
        val data = Random(7).nextBytes(700_000)
        File(payloadDir, "video.mp4").writeBytes(data)
        val torrent = singleFileTorrent("video.mp4", data, 64 * 1024)
        val engine = LibtorrentEngine(FileResumeStore(File(dir, "resume")), File(dir, "work"))
        val port = ServerSocket(0).use { it.localPort }
        engine.start(EngineConfig(AppSettings(enableDht = false, enableLsd = false, enableUpnp = false, enableNatPmp = false, autoPort = false, preallocate = false), port, 2, bindAddresses = listOf("127.0.0.1")))
        val id = engine.add(AddRequest(AddSource.TorrentFile(Base64Util.encode(torrent))), payloadDir.absolutePath, emptyList())
        withTimeout(30_000) { engine.torrents.first { it[id]?.state == TorrentState.COMPLETED } }

        val streams = StreamServer(engine, scope)
        val url = streams.url(id, 0, "video.mp4")
        val (code, body, contentRange) = get(url, "bytes=100000-100999")
        assertEquals(206, code)
        assertEquals("bytes 100000-100999/700000", contentRange)
        assertContentEquals(data.copyOfRange(100000, 101000), body)
        val full = get(url, null)
        assertEquals(200, full.first)
        assertContentEquals(data, full.second)
        assertEquals(404, get(url.replace("/stream/", "/stream/x"), null).first)
        streams.stop()
        engine.stop()
    }

    private fun get(url: String, range: String?): Triple<Int, ByteArray, String?> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { it.copyTo(out) }
            return Triple(code, out.toByteArray(), c.getHeaderField("Content-Range"))
        } finally {
            c.disconnect()
        }
    }

    private fun singleFileTorrent(name: String, data: ByteArray, pieceLength: Int): ByteArray {
        val pieces = ByteArrayOutputStream()
        var o = 0
        while (o < data.size) {
            val e = minOf(o + pieceLength, data.size)
            pieces.write(MessageDigest.getInstance("SHA-1").apply { update(data, o, e - o) }.digest())
            o = e
        }
        val p = pieces.toByteArray()
        val out = ByteArrayOutputStream()
        fun s(v: String) = out.write("${v.toByteArray().size}:$v".toByteArray())
        out.write("d4:infod".toByteArray())
        s("length"); out.write("i${data.size}e".toByteArray())
        s("name"); s(name)
        s("piece length"); out.write("i${pieceLength}e".toByteArray())
        s("pieces"); out.write("${p.size}:".toByteArray()); out.write(p)
        out.write("ee".toByteArray())
        return out.toByteArray()
    }

    @Suppress("unused")
    private suspend fun settle() = delay(100)
}
