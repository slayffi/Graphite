package com.graphite.torrent.data

import app.cash.sqldelight.db.SqlDriver
import com.graphite.torrent.core.engine.FileResumeStore
import com.graphite.torrent.core.engine.LibtorrentEngine
import com.graphite.torrent.data.automation.FileWatchFolderSource
import com.graphite.torrent.data.automation.RssService
import com.graphite.torrent.data.automation.SchedulerService
import com.graphite.torrent.data.automation.WatchFolderService
import com.graphite.torrent.data.automation.WatchFolderSource
import com.graphite.torrent.data.backup.BackupService
import com.graphite.torrent.data.db.GraphiteDatabase
import com.graphite.torrent.data.remote.RemoteServer
import com.graphite.torrent.data.stream.StreamServer
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.repo.HistoryRepository
import com.graphite.torrent.data.repo.StatsRepository
import com.graphite.torrent.data.repo.TorrentMetaRepository
import com.graphite.torrent.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Object graph shared by the Android and desktop apps (manual dependency injection). */
class GraphiteServices(
    val scope: CoroutineScope,
    val dataDir: File,
    val database: GraphiteDatabase,
    val settings: SettingsRepository,
    val engine: LibtorrentEngine,
    val meta: TorrentMetaRepository,
    val stats: StatsRepository,
    val eventLog: EventLogRepository,
    val history: HistoryRepository,
    val storage: PlatformStorage,
    val controller: LocalTorrentController,
    /** What the UI talks to: the local engine, or a paired desktop in remote mode. */
    val switchable: SwitchableTorrentController,
    val scheduler: SchedulerService,
    val rss: RssService,
    val watchFolder: WatchFolderService,
    val backup: BackupService,
    val streams: StreamServer,
    val remoteServer: RemoteServer,
) {
    /** Starts scheduler, RSS and the watched folder. */
    fun startAutomation() {
        scheduler.start()
        rss.start()
        watchFolder.start()
    }

    fun stopAutomation() {
        scheduler.stop()
        rss.stop()
        watchFolder.stop()
    }

    companion object {
        fun create(
            dataDir: File,
            driver: SqlDriver,
            storage: PlatformStorage,
            nativeExtractDir: String? = null,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            watchSources: List<WatchFolderSource> = listOf(FileWatchFolderSource()),
        ): GraphiteServices {
            dataDir.mkdirs()
            val database = GraphiteDatabase(driver)
            val settings = SettingsRepository.create(File(dataDir, "settings.json").absolutePath, scope)
            val engine = LibtorrentEngine(
                resumeStore = FileResumeStore(File(dataDir, "resume")),
                workDir = File(dataDir, "work"),
                nativeExtractDir = nativeExtractDir,
            )
            val meta = TorrentMetaRepository(database)
            val stats = StatsRepository(database)
            val eventLog = EventLogRepository(database)
            val history = HistoryRepository(database)
            val controller = LocalTorrentController(
                engine = engine,
                settingsRepository = settings,
                meta = meta,
                stats = stats,
                eventLog = eventLog,
                history = history,
                storage = storage,
                scope = scope,
            )
            val scheduler = SchedulerService(database, settings, controller, eventLog, scope)
            val rss = RssService(database, settings, controller, eventLog, scope)
            val watchFolder = WatchFolderService(settings, controller, eventLog, scope, watchSources)
            val backup = BackupService(engine, settings, meta, scheduler, rss, eventLog)
            return GraphiteServices(
                scope, dataDir, database, settings, engine, meta, stats, eventLog, history, storage, controller,
                switchable = SwitchableTorrentController(controller, scope),
                scheduler = scheduler,
                rss = rss,
                watchFolder = watchFolder,
                backup = backup,
                streams = StreamServer(engine, scope),
                remoteServer = RemoteServer(controller, settings, eventLog, scope),
            )
        }
    }
}
