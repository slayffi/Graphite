package com.graphite.torrent.data

import com.graphite.torrent.core.model.Category
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.model.HistoryEvent
import com.graphite.torrent.core.model.LogLevel
import com.graphite.torrent.core.model.StatsPeriod
import com.graphite.torrent.data.db.GraphiteDatabase
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.repo.HistoryRepository
import com.graphite.torrent.data.repo.StatsRepository
import com.graphite.torrent.data.repo.TorrentMetaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoriesTest {
    private val db = GraphiteDatabase(DatabaseFactory.createInMemory())

    @Test
    fun torrentMetadataTagsAndCategories() = runBlocking {
        val repo = TorrentMetaRepository(db)
        repo.insert("a", "Alpha", "Movies", 10, "/dl", 100, "magnet:?x", listOf("hd", " hd ", "new"))
        assertEquals("Movies", repo.get("a")?.category)
        assertEquals(listOf("hd", "new"), repo.tagsOf("a"))
        repo.setTags("a", listOf("4k"))
        assertEquals(mapOf("a" to listOf("4k")), repo.tags.first())
        assertTrue(repo.markCompleted("a", 99))
        assertFalse(repo.markCompleted("a", 100), "second completion is ignored")
        assertEquals(99, repo.get("a")?.completed_at)

        repo.saveCategory(Category("Movies", "/movies"))
        assertEquals(listOf(Category("Movies", "/movies")), repo.categories.first())
        repo.deleteCategory("Movies")
        assertEquals(null, repo.get("a")?.category)

        repo.delete("a")
        assertTrue(repo.rows.first().isEmpty())
        assertTrue(repo.tags.first().isEmpty())
    }

    @Test
    fun dailyStatsAccumulate() = runBlocking {
        var day = 100L
        val stats = StatsRepository(db, today = { day })
        stats.addTraffic(1000, 0, 10, 500)
        stats.addTraffic(500, 0, 5, 800)
        stats.incrementCompleted()
        day = 101
        stats.addTraffic(42, 0, 1, 10)
        val week = stats.days(StatsPeriod.WEEK).first()
        assertEquals(7, week.size)
        assertEquals(1500, week.first { it.day == 100L }.downloaded)
        assertEquals(800, week.first { it.day == 100L }.peakRate)
        val totals = stats.totals(StatsPeriod.WEEK).first()
        assertEquals(1542, totals.downloaded)
        assertEquals(0, totals.uploaded)
        assertEquals(1, totals.completed)
        assertEquals(42, stats.totals(StatsPeriod.DAY).first().downloaded)
    }

    @Test
    fun eventLogAndHistory() = runBlocking {
        val log = EventLogRepository(db, clock = { 5 })
        log.log(EventType.TORRENT_ADDED, "x", torrentId = "id")
        log.log(EventType.PORT_CHANGED, "1234", level = LogLevel.WARNING)
        val entries = log.recent().first()
        assertEquals(listOf(EventType.PORT_CHANGED, EventType.TORRENT_ADDED), entries.map { it.type })
        assertEquals(LogLevel.WARNING, entries.first().level)

        val history = HistoryRepository(db, clock = { 7 })
        history.record("id", "x", 10, HistoryEvent.COMPLETED, "/dl")
        assertEquals(HistoryEvent.COMPLETED, history.recent().first().single().event)
    }
}
