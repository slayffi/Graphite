package com.graphite.torrent.data

import app.cash.sqldelight.db.SqlDriver
import com.graphite.torrent.core.engine.FileResumeStore
import com.graphite.torrent.core.engine.LibtorrentEngine
import com.graphite.torrent.data.db.GraphiteDatabase
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
) {
    companion object {
        fun create(
            dataDir: File,
            driver: SqlDriver,
            storage: PlatformStorage,
            nativeExtractDir: String? = null,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
            return GraphiteServices(scope, dataDir, database, settings, engine, meta, stats, eventLog, history, storage, controller)
        }
    }
}
