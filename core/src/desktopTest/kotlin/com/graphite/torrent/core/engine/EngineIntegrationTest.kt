package com.graphite.torrent.core.engine

import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.AddSource
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.core.util.Base64Util
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.address
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.tcp_endpoint
import java.io.File
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end test on localhost with three real libtorrent sessions:
 * a seeder, Graphite (zero-upload) and an ordinary leecher that keeps asking Graphite for pieces.
 * Graphite must download everything while uploading exactly 0 payload bytes, then stop (no seeding).
 */
class EngineIntegrationTest {
    private lateinit var root: File
    private val plainSessions = ArrayList<SessionManager>()

    @BeforeTest
    fun setUp() {
        NativeLibraries.ensureLoaded(null)
        root = createTempDirectory("graphite-it").toFile()
    }

    @AfterTest
    fun tearDown() {
        plainSessions.forEach { runCatching { it.stop() } }
        root.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun plainSession(port: Int, uploadLimit: Int): SessionManager {
        val pack = SettingsPack()
        pack.setString(SettingNames.string("listen_interfaces"), "127.0.0.1:$port")
        pack.setBoolean(SettingNames.bool("enable_dht"), false)
        pack.setBoolean(SettingNames.bool("enable_lsd"), false)
        pack.setBoolean(SettingNames.bool("enable_upnp"), false)
        pack.setBoolean(SettingNames.bool("enable_natpmp"), false)
        pack.setBoolean(SettingNames.bool("allow_multiple_connections_per_ip"), true)
        pack.setInteger(SettingNames.int("upload_rate_limit"), uploadLimit)
        val sm = SessionManager(false)
        sm.start(SessionParams(pack))
        plainSessions += sm
        return sm
    }

    private fun addPlain(sm: SessionManager, torrent: ByteArray, saveDir: File, seed: Boolean): TorrentHandle {
        val params = AddTorrentParams()
        params.setTorrentInfo(TorrentInfo(torrent))
        params.setSavePath(saveDir.absolutePath)
        params.setFlags(if (seed) TorrentFlags.SEED_MODE else TorrentFlags.AUTO_MANAGED)
        val ec = error_code()
        val handle = sm.swig().add_torrent(params.swig(), ec)
        assertTrue(!ec.failed(), ec.message())
        return TorrentHandle(handle)
    }

    private fun connect(handle: TorrentHandle, port: Int) {
        val ec = error_code()
        handle.swig().connect_peer(tcp_endpoint(address.from_string("127.0.0.1", ec), port))
    }

    private fun graphiteEngine(dir: File, port: Int): LibtorrentEngine =
        LibtorrentEngine(FileResumeStore(File(dir, "resume")), File(dir, "work"))

    private fun graphiteConfig(port: Int) = EngineConfig(
        settings = AppSettings(
            zeroUpload = true,
            enableDht = false,
            enableLsd = false,
            enableUpnp = false,
            enableNatPmp = false,
            enableIpv6 = false,
            autoPort = false,
            addPublicTrackers = false,
            preallocate = false,
        ),
        listenPort = port,
        cpuCores = 2,
        bindAddresses = listOf("127.0.0.1"),
    )

    private suspend fun LibtorrentEngine.awaitItem(id: String, predicate: (TorrentItem) -> Boolean): TorrentItem =
        torrents.first { map -> map[id]?.let(predicate) == true }.getValue(id)

    @Test
    fun downloadsWithZeroPayloadUploadAndStopsAfterCompletion() = runBlocking {
        val payload = TestTorrents.createPayload(File(root, "seed"), "data.bin", 6 * 1024 * 1024)
        val torrent = TestTorrents.torrentFor(payload)

        val seederPort = freePort()
        val graphitePort = freePort()
        val leecherPort = freePort()

        // The seeder is slow so the ordinary leecher has every reason to ask Graphite for pieces.
        val seeder = plainSession(seederPort, uploadLimit = 2 * 1024 * 1024)
        val seedHandle = addPlain(seeder, torrent, File(root, "seed"), seed = true)

        val engine = graphiteEngine(File(root, "graphite-data"), graphitePort)
        engine.start(graphiteConfig(graphitePort))
        engine.applyRawSettings(SettingsPack().apply {
            setBoolean(SettingNames.bool("allow_multiple_connections_per_ip"), true)
        })
        val graphiteDownloads = File(root, "graphite-downloads")
        val id = engine.add(
            AddRequest(AddSource.TorrentFile(Base64Util.encode(torrent))),
            graphiteDownloads.absolutePath,
            emptyList(),
        )

        val leecher = plainSession(leecherPort, uploadLimit = 0)
        val leechHandle = addPlain(leecher, torrent, File(root, "leech"), seed = false)

        withTimeout(120_000) {
            while (true) {
                connect(seedHandle, graphitePort)
                connect(seedHandle, leecherPort)
                connect(leechHandle, graphitePort)
                val item = engine.torrents.value[id]
                if (item?.state == TorrentState.COMPLETED) break
                delay(500)
            }
        }

        val done = engine.awaitItem(id) { it.state == TorrentState.COMPLETED }
        assertEquals(1f, done.progress)
        assertContentEquals(payload.readBytes(), File(graphiteDownloads, "data.bin").readBytes())

        // Zero upload: nothing was sent to the ordinary leecher, even though it is on the "local" network.
        delay(3000)
        val after = engine.torrents.value.getValue(id)
        assertEquals(0L, after.uploadedPayload, "Graphite must not upload payload")
        assertEquals(0L, engine.session.value.sessionUploaded)
        val leecherFromGraphite = leechHandle.peerInfo().filter { it.ip().contains(":$graphitePort") }.sumOf { it.totalDownload() }
        assertEquals(0L, leecherFromGraphite, "leecher must not receive data from Graphite")

        // No seeding: the completed torrent is paused and out of the auto-managed queue.
        assertEquals(TorrentState.COMPLETED, after.state)
        assertTrue(engine.peers(id).isEmpty(), "paused torrent keeps no peer connections")

        // Resume data survives a restart.
        engine.stop()
        val restarted = graphiteEngine(File(root, "graphite-data"), graphitePort)
        restarted.start(graphiteConfig(graphitePort))
        val restored = withTimeout(30_000) { restarted.awaitItem(id) { it.state == TorrentState.COMPLETED } }
        assertEquals(6L * 1024 * 1024, restored.wantedBytes)
        assertEquals(0L, restored.uploadedPayload)
        restarted.stop()
    }

    @Test
    fun filePrioritiesAndDuplicateDetection() = runBlocking {
        val payload = TestTorrents.createPayload(File(root, "src"), "a.bin", 512 * 1024)
        val torrent = TestTorrents.torrentFor(payload, pieceLength = 64 * 1024)
        val port = freePort()
        val engine = graphiteEngine(File(root, "g"), port)
        engine.start(graphiteConfig(port))
        val target = File(root, "dl").absolutePath
        val request = AddRequest(AddSource.TorrentFile(Base64Util.encode(torrent)), filePriorities = mapOf(0 to FilePriority.HIGH))
        val id = engine.add(request, target, emptyList())
        assertEquals(FilePriority.HIGH, engine.files(id).single().priority)

        val error = assertFailsWith<com.graphite.torrent.core.model.GraphiteException> {
            engine.add(request, target, emptyList())
        }
        assertEquals(com.graphite.torrent.core.model.ErrorKind.DUPLICATE, error.error.kind)

        engine.setFilePriorities(id, mapOf(0 to FilePriority.SKIP))
        // prioritize_files() is applied asynchronously on libtorrent's network thread.
        withTimeout(10_000) { while (engine.files(id).single().priority != FilePriority.SKIP) delay(50) }

        engine.pause(id)
        engine.awaitItem(id) { it.state == TorrentState.PAUSED || it.state == TorrentState.COMPLETED }
        engine.remove(id, deleteFiles = true)
        assertTrue(engine.torrents.value[id] == null)
        engine.stop()
    }

    @Test
    fun rejectsInvalidInput() = runBlocking {
        val port = freePort()
        val engine = graphiteEngine(File(root, "g2"), port)
        engine.start(graphiteConfig(port))
        val bad = assertFailsWith<com.graphite.torrent.core.model.GraphiteException> {
            engine.add(AddRequest(AddSource.Magnet("magnet:?xt=urn:btih:zz")), File(root, "x").absolutePath, emptyList())
        }
        assertEquals(com.graphite.torrent.core.model.ErrorKind.INVALID_TORRENT, bad.error.kind)
        val garbage = assertFailsWith<com.graphite.torrent.core.model.GraphiteException> {
            engine.parseTorrentFile(byteArrayOf(1, 2, 3))
        }
        assertEquals(com.graphite.torrent.core.model.ErrorKind.INVALID_TORRENT, garbage.error.kind)
        engine.stop()
    }
}
