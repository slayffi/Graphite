package com.graphite.torrent.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.model.LogEntry
import com.graphite.torrent.core.model.LogLevel
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.data.db.GraphiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Persistent event log, capped at [MAX_ENTRIES]. */
class EventLogRepository(
    private val db: GraphiteDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val q get() = db.eventLogQueries
    private var writes = 0

    fun recent(limit: Long = 500): Flow<List<LogEntry>> =
        q.selectRecent(limit).asFlow().mapToList(io).map { rows ->
            rows.map {
                LogEntry(
                    id = it.id,
                    timestamp = it.timestamp,
                    level = runCatching { LogLevel.valueOf(it.level) }.getOrDefault(LogLevel.INFO),
                    type = runCatching { EventType.valueOf(it.type) }.getOrDefault(EventType.TORRENT_ERROR),
                    torrentId = it.torrent_id,
                    subject = it.subject,
                    detail = it.detail,
                )
            }
        }

    suspend fun log(type: EventType, subject: String = "", detail: String = "", torrentId: String? = null, level: LogLevel = LogLevel.INFO) =
        withContext(io) {
            q.insert(clock(), level.name, type.name, torrentId, subject, detail)
            if (++writes % 100 == 0) q.trim(MAX_ENTRIES)
        }

    suspend fun error(type: EventType, subject: String, error: TorrentError, torrentId: String? = null) =
        log(type, subject, "${error.kind.name}: ${error.detail}", torrentId, LogLevel.ERROR)

    suspend fun clear() = withContext(io) { q.clearAll() }

    companion object {
        const val MAX_ENTRIES = 2000L
    }
}
