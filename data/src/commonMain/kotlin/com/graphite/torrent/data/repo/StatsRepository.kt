package com.graphite.torrent.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.graphite.torrent.core.model.DailyStat
import com.graphite.torrent.core.model.StatsPeriod
import com.graphite.torrent.core.model.StatsTotals
import com.graphite.torrent.data.db.GraphiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Daily traffic statistics (local days). */
class StatsRepository(
    private val db: GraphiteDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> Long = { LocalDate.now(ZoneId.systemDefault()).toEpochDay() },
) {
    private val q get() = db.statsQueries

    fun currentDay(): Long = today()

    suspend fun addTraffic(downloaded: Long, uploaded: Long, activeSeconds: Long, peakRate: Long, day: Long = today()) =
        withContext(io) {
            db.transaction {
                q.ensureDay(day)
                q.addTraffic(downloaded.coerceAtLeast(0), uploaded.coerceAtLeast(0), activeSeconds, peakRate, day)
            }
        }

    suspend fun incrementCompleted(day: Long = today()) = withContext(io) {
        db.transaction {
            q.ensureDay(day)
            q.incrementCompleted(day)
        }
    }

    suspend fun incrementAdded(day: Long = today()) = withContext(io) {
        db.transaction {
            q.ensureDay(day)
            q.incrementAdded(day)
        }
    }

    private fun range(period: StatsPeriod): LongRange {
        val end = today()
        return (end - period.days + 1)..end
    }

    fun days(period: StatsPeriod): Flow<List<DailyStat>> {
        val r = range(period)
        return q.selectRange(r.first, r.last).asFlow().mapToList(io).map { rows ->
            val byDay = rows.associateBy { it.day }
            r.map { day ->
                byDay[day]?.let {
                    DailyStat(it.day, it.downloaded, it.uploaded, it.completed, it.added, it.peak_rate, it.active_seconds)
                } ?: DailyStat(day, 0, 0, 0, 0, 0, 0)
            }
        }
    }

    fun totals(period: StatsPeriod): Flow<StatsTotals> {
        val r = range(period)
        return q.totals(r.first, r.last).asFlow().mapToOne(io).map {
            StatsTotals(it.downloaded, it.uploaded, it.completed, it.added, it.peak_rate, it.active_seconds)
        }
    }

    suspend fun all(): List<DailyStat> = withContext(io) {
        q.selectRange(Long.MIN_VALUE, Long.MAX_VALUE).executeAsList().map {
            DailyStat(it.day, it.downloaded, it.uploaded, it.completed, it.added, it.peak_rate, it.active_seconds)
        }
    }

    suspend fun clear() = withContext(io) { q.clearAll() }
}
