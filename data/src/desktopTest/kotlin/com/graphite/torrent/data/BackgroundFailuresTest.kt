package com.graphite.torrent.data

import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.model.LogLevel
import com.graphite.torrent.data.db.GraphiteDatabase
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.repo.HistoryRepository
import com.graphite.torrent.data.repo.StatsRepository
import com.graphite.torrent.data.repo.TorrentMetaRepository
import com.graphite.torrent.data.settings.SettingsRepository
import com.graphite.torrent.testing.FakeTorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Android an uncaught exception in a background coroutine ends the process, so "the tracker list
 * could not be downloaded" used to close the app right after start. These tests fail on any exception
 * that reaches the thread's uncaught handler.
 */
class BackgroundFailuresTest {
    @Test
    fun unreachableTrackerListDoesNotEscapeTheController() = runBlocking {
        val dir = createTempDirectory("bg").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val db = GraphiteDatabase(DatabaseFactory.createInMemory())
            val settings = SettingsRepository.create(File(dir, "s.json").absolutePath, scope)
            // Nothing listens on this port: the download fails at once, like without a network.
            settings.update { it.copy(trackerSources = listOf("http://127.0.0.1:9/trackers.txt"), trackersAutoUpdate = true, trackersUpdatedAt = 0) }
            val eventLog = EventLogRepository(db)
            val controller = LocalTorrentController(
                FakeTorrentEngine(), settings, TorrentMetaRepository(db), StatsRepository(db), eventLog, HistoryRepository(db),
                FileSystemStorage(File(dir, "downloads").absolutePath), scope,
            )
            controller.start()
            val failed = withTimeout(10_000) {
                eventLog.recent().first { list -> list.any { it.type == EventType.TRACKERS_UPDATE_FAILED } }
            }
            assertTrue(failed.any { it.level == LogLevel.ERROR })
            delay(300)
            assertEquals(emptyList(), uncaught.map { it.toString() }, "the failure must stay inside the controller")
            controller.stop()
        } finally {
            scope.cancel()
            Thread.setDefaultUncaughtExceptionHandler(previous)
            dir.deleteRecursively()
        }
    }

    @Test
    fun unexpectedBackgroundErrorIsLoggedInsteadOfCrashing() = runBlocking {
        val dir = createTempDirectory("bg2").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        var services: GraphiteServices? = null
        try {
            services = GraphiteServices.create(
                dataDir = File(dir, "data"),
                driver = DatabaseFactory.createInMemory(),
                storage = FileSystemStorage(File(dir, "downloads").absolutePath),
            )
            services.scope.launch { error("boom") }
            val logged = withTimeout(10_000) {
                services.eventLog.recent().first { list -> list.any { it.type == EventType.INTERNAL_ERROR } }
            }
            assertTrue(logged.first { it.type == EventType.INTERNAL_ERROR }.detail.contains("boom"))
            assertEquals(1, services.backgroundErrors.count)
            assertEquals(emptyList(), uncaught.map { it.toString() })
        } finally {
            services?.scope?.cancel()
            Thread.setDefaultUncaughtExceptionHandler(previous)
            dir.deleteRecursively()
        }
    }
}
