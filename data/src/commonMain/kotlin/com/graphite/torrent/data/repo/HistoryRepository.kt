package com.graphite.torrent.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.graphite.torrent.core.model.HistoryEntry
import com.graphite.torrent.core.model.HistoryEvent
import com.graphite.torrent.data.db.GraphiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class HistoryRepository(
    private val db: GraphiteDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val q get() = db.historyQueries

    fun recent(limit: Long = 300): Flow<List<HistoryEntry>> =
        q.selectRecent(limit).asFlow().mapToList(io).map { rows ->
            rows.map {
                HistoryEntry(
                    id = it.id,
                    torrentId = it.torrent_id,
                    name = it.name,
                    size = it.size,
                    event = runCatching { HistoryEvent.valueOf(it.event) }.getOrDefault(HistoryEvent.ADDED),
                    timestamp = it.timestamp,
                    location = it.location,
                )
            }
        }

    suspend fun record(torrentId: String, name: String, size: Long, event: HistoryEvent, location: String = "") =
        withContext(io) { q.insert(torrentId, name, size, event.name, clock(), location) }

    suspend fun clear() = withContext(io) { q.clearAll() }
}
