package com.graphite.torrent.data

import app.cash.sqldelight.db.SqlDriver
import com.graphite.torrent.core.engine.ErrorMapper
import com.graphite.torrent.core.engine.FileResumeStore
import com.graphite.torrent.core.engine.LibtorrentEngine
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.data.automation.FileWatchFolderSource
import com.graphite.torrent.data.automation.RssService
import com.graphite.torrent.data.automation.SchedulerService
import com.graphite.torrent.data.automation.WatchFolderService
import com.graphite.torrent.data.automation.WatchFolderSource
import com.graphite.torrent.data.backup.BackupService
import com.graphite.torrent.data.db.GraphiteDatabase
import com.graphite.torrent.data.remote.RemoteServer
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.repo.HistoryRepository
import com.graphite.torrent.data.repo.StatsRepository
import com.graphite.torrent.data.repo.TorrentMetaRepository
import com.graphite.torrent.data.settings.SettingsRepository
import com.graphite.torrent.data.stream.StreamServer
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    /** Unexpected failures of background jobs (they are logged, the app keeps running). */
    val backgroundErrors: BackgroundErrors,
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
            scope: CoroutineScope? = null,
            watchSources: List<WatchFolderSource> = listOf(FileWatchFolderSource()),
        ): GraphiteServices {
            // A failed background job (network, disk, a bug) must not take the whole app down: on Android an
            // uncaught coroutine exception kills the process. It is written to the event log instead.
            val errors = BackgroundErrors()
            @Suppress("NAME_SHADOWING")
            val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)
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
            errors.sink = { e ->
                e.printStackTrace()
                scope.launch {
                    runCatching { eventLog.error(EventType.INTERNAL_ERROR, e.javaClass.simpleName, ErrorMapper.fromException(e)) }
                }
            }
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
                backgroundErrors = errors,
            )
        }
    }
}

/** Last-resort handler of the background scope: reports instead of crashing. */
class BackgroundErrors : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    @Volatile var sink: (Throwable) -> Unit = { it.printStackTrace() }

    /** Number of failures seen (tests use it to assert a clean run). */
    @Volatile var count: Int = 0
        private set

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        count++
        runCatching { sink(exception) }
    }
}
