package com.graphite.torrent.data

import com.graphite.torrent.core.controller.ControllerNotice
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.AddSource
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.HistoryEvent
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.model.StatsPeriod
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.data.db.GraphiteDatabase
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.repo.HistoryRepository
import com.graphite.torrent.data.repo.StatsRepository
import com.graphite.torrent.data.repo.TorrentMetaRepository
import com.graphite.torrent.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalTorrentControllerTest {
    private val dir = createTempDirectory("ctrl").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = GraphiteDatabase(DatabaseFactory.createInMemory())
    private val engine = FakeTorrentEngine()
    private val settings = SettingsRepository.create(File(dir, "s.json").absolutePath, scope)
    private val meta = TorrentMetaRepository(db)
    private val stats = StatsRepository(db, today = { 1L })
    private val history = HistoryRepository(db)
    private var storage: PlatformStorage = FileSystemStorage(File(dir, "downloads").absolutePath)
    private val relocatingStorage = object : FileSystemStorage(File(dir, "downloads").absolutePath) {
        override suspend fun afterCompletion(item: TorrentItem, files: List<FileEntry>, settings: AppSettings) =
            CompletionResult.Relocated("content://tree/${item.id}")
    }

    private fun controller(s: PlatformStorage = storage) = LocalTorrentController(
        engine, settings, meta, stats, EventLogRepository(db), history, s, scope,
    )

    private val magnet = "magnet:?xt=urn:btih:c9e15763f722f23e98a29decdfae341b98d53056&dn=Sintel"

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun startPicksPortAndAddRecordsEverything() = runBlocking {
        val c = controller()
        c.start()
        val port = settings.current().listenPort
        assertTrue(port in 20_000 until 60_000, "random port chosen on first start")
        assertEquals(port, engine.lastConfig?.listenPort)

        val id = c.add(AddRequest(AddSource.Magnet(magnet), category = "Movies", tags = listOf("hd")))
        assertEquals(AppSettings.DEFAULT_TRACKERS, engine.lastExtraTrackers)
        assertTrue(engine.lastSavePath.endsWith("downloads"))
        val item = withTimeout(5000) { c.torrents.first { list -> list.any { it.id == id && it.category == "Movies" } } }.first()
        assertEquals(listOf("hd"), item.tags)
        assertEquals("Sintel", meta.get(id)?.name)
        assertEquals(HistoryEvent.ADDED, history.recent().first().first().event)
        assertEquals(1, stats.totals(StatsPeriod.DAY).first().added)

        val dup = assertFailsWith<GraphiteException> { c.previewMagnet(magnet) }
        assertEquals(ErrorKind.DUPLICATE, dup.error.kind)
        c.stop()
    }

    @Test
    fun completionIsRecordedOnceAndNotified() = runBlocking {
        val c = controller()
        c.start()
        val notices = ArrayList<ControllerNotice>()
        val job = scope.launchCollect(c, notices)
        val id = c.add(AddRequest(AddSource.Magnet(magnet)))
        engine.complete(id)
        withTimeout(5000) { while (notices.none { it is ControllerNotice.Completed }) kotlinx.coroutines.delay(20) }
        engine.complete(id)
        kotlinx.coroutines.delay(200)
        assertEquals(1, notices.count { it is ControllerNotice.Completed })
        assertEquals(1, stats.totals(StatsPeriod.DAY).first().completed)
        job.cancel()
        c.stop()
    }

    @Test
    fun relocatedDownloadsStayInTheListAsArchived() = runBlocking {
        val c = controller(relocatingStorage)
        c.start()
        val id = c.add(AddRequest(AddSource.Magnet(magnet)))
        engine.complete(id)
        val archived = withTimeout(5000) {
            c.torrents.first { list -> list.any { it.id == id && it.finalLocation != null } && id !in engine.state.value }
        }.single()
        assertEquals(TorrentState.COMPLETED, archived.state)
        assertEquals("content://tree/$id", archived.finalLocation)
        assertTrue(id !in engine.state.value, "engine no longer tracks the relocated torrent")

        c.remove(listOf(id), deleteFiles = false)
        withTimeout(5000) { c.torrents.first { it.isEmpty() } }
        c.stop()
    }

    @Test
    fun moveCompletedToFolder() = runBlocking {
        settings.update { it.copy(moveCompletedTo = File(dir, "done").absolutePath) }
        val c = controller()
        c.start()
        val id = c.add(AddRequest(AddSource.Magnet(magnet)))
        engine.complete(id)
        withTimeout(5000) { while (engine.moves.isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(id to File(dir, "done").absolutePath, engine.moves.single())
        c.stop()
    }

    @Test
    fun pauseReasonsReachTheEngine() = runBlocking {
        val c = controller()
        c.start()
        c.setPauseReason(PauseReason.NO_WIFI, true)
        withTimeout(5000) { while (PauseReason.NO_WIFI !in engine.appliedPauseReasons) kotlinx.coroutines.delay(20) }
        c.setPauseReason(PauseReason.NO_WIFI, false)
        withTimeout(5000) { while (engine.appliedPauseReasons.isNotEmpty()) kotlinx.coroutines.delay(20) }
        c.stop()
    }

    @Test
    fun invalidInputIsClassified() = runBlocking {
        val c = controller()
        c.start()
        assertEquals(ErrorKind.INVALID_TORRENT, assertFailsWith<GraphiteException> { c.previewMagnet("magnet:?xt=bad") }.error.kind)
        assertEquals(ErrorKind.INVALID_TORRENT, assertFailsWith<GraphiteException> { c.previewTorrentFile(ByteArray(0)) }.error.kind)
        c.stop()
    }

    private fun CoroutineScope.launchCollect(c: LocalTorrentController, sink: MutableList<ControllerNotice>) =
        launch { c.notices.collect { synchronized(sink) { sink += it } } }
}
