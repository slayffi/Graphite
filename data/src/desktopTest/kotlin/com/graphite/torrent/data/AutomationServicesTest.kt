package com.graphite.torrent.data

import com.graphite.torrent.core.model.Category
import com.graphite.torrent.core.model.RssRule
import com.graphite.torrent.core.model.ScheduleAction
import com.graphite.torrent.core.model.ScheduleRule
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.settings.SpeedProfile
import com.graphite.torrent.core.settings.WatchFolderAction
import com.graphite.torrent.data.automation.RssService
import com.graphite.torrent.data.automation.SchedulerService
import com.graphite.torrent.data.automation.WatchFolderService
import com.graphite.torrent.data.backup.BackupService
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutomationServicesTest {
    private val dir = createTempDirectory("auto").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private inner class Env(name: String) {
        val db = GraphiteDatabase(DatabaseFactory.createInMemory())
        val engine = FakeTorrentEngine()
        val settings = SettingsRepository.create(File(dir, "$name.json").absolutePath, scope)
        val log = EventLogRepository(db)
        val meta = TorrentMetaRepository(db)
        val controller = LocalTorrentController(engine, settings, meta, StatsRepository(db), log, HistoryRepository(db), FileSystemStorage(dir.absolutePath), scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun schedulerAppliesPauseAndLimits() = runBlocking {
        val env = Env("sched")
        env.controller.start()
        var now = LocalDateTime.of(2026, 11, 23, 10, 0)
        val scheduler = SchedulerService(env.db, env.settings, env.controller, env.log, scope) { now }
        env.settings.update { it.copy(schedulerEnabled = true) }
        scheduler.save(ScheduleRule(days = ScheduleRule.ALL_DAYS, startMinute = 9 * 60, endMinute = 11 * 60, action = ScheduleAction.PAUSE))
        scheduler.save(ScheduleRule(days = ScheduleRule.ALL_DAYS, startMinute = 12 * 60, endMinute = 13 * 60, action = ScheduleAction.LIMIT, downloadKbps = 300))
        scheduler.save(ScheduleRule(days = ScheduleRule.ALL_DAYS, startMinute = 12 * 60, endMinute = 13 * 60, action = ScheduleAction.PROFILE, profile = SpeedProfile.ECONOMY))
        assertTrue(scheduler.decision.value.pause)
        kotlinx.coroutines.withTimeout(5000) { while (PauseReason.SCHEDULE !in env.engine.appliedPauseReasons) kotlinx.coroutines.delay(20) }

        now = now.withHour(12).withMinute(30)
        scheduler.tick()
        assertEquals(300, env.controller.runtimeOverrides.value.downloadLimitKbps)
        assertEquals(SpeedProfile.ECONOMY, env.controller.runtimeOverrides.value.profile)
        kotlinx.coroutines.withTimeout(5000) { while (env.engine.appliedPauseReasons.isNotEmpty()) kotlinx.coroutines.delay(20) }
        kotlinx.coroutines.withTimeout(5000) { while (env.engine.lastConfig?.downloadLimitOverrideKbps != 300) kotlinx.coroutines.delay(20) }
        env.controller.stop()
    }

    @Test
    fun rssDownloadsOnlyNewMatchingItems() = runBlocking {
        val env = Env("rss")
        env.controller.start()
        var feedXml = feed(listOf("Show S01E01 1080p" to "c9e15763f722f23e98a29decdfae341b98d53056"))
        val rss = RssService(env.db, env.settings, env.controller, env.log, scope, fetcher = { feedXml.toByteArray() })
        rss.addFeed("https://example.org/feed")
        rss.saveRule(RssRule(name = "show", include = "show 1080p", category = "Series"))
        assertEquals(0, rss.refreshAll(), "items present when the feed was added are not downloaded")
        feedXml = feed(listOf(
            "Show S01E01 1080p" to "c9e15763f722f23e98a29decdfae341b98d53056",
            "Show S01E02 1080p" to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "Show S01E02 720p" to "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        ))
        assertEquals(1, rss.refreshAll())
        assertTrue("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" in env.engine.state.value)
        assertEquals("Series", env.meta.get("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")?.category)
        assertEquals(0, rss.refreshAll(), "nothing new")
        assertEquals(3, rss.recentItems.first().size)
        env.controller.stop()
    }

    private fun feed(items: List<Pair<String, String>>) = buildString {
        append("<rss><channel><title>T</title>")
        items.forEach { (title, hash) -> append("<item><title>$title</title><guid>$hash</guid><link>magnet:?xt=urn:btih:$hash</link></item>") }
        append("</channel></rss>")
    }

    @Test
    fun watchFolderAddsAndRenames() = runBlocking {
        val env = Env("watch")
        env.controller.start()
        val watched = File(dir, "watched").apply { mkdirs() }
        File(watched, "one.torrent").writeBytes(byteArrayOf(1, 2, 3))
        File(watched, "broken.torrent").writeBytes(ByteArray(0))
        File(watched, "note.txt").writeText("ignore me")
        env.settings.update { it.copy(watchFolderEnabled = true, watchFolderPath = watched.absolutePath, watchFolderAction = WatchFolderAction.RENAME) }
        val service = WatchFolderService(env.settings, env.controller, env.log, scope)
        assertEquals(1, service.scanOnce())
        val names = watched.list()!!.toSet()
        assertTrue("one.torrent.added" in names, names.toString())
        assertTrue("broken.torrent.invalid" in names, names.toString())
        assertTrue("note.txt" in names)
        assertEquals(0, service.scanOnce())
        env.controller.stop()
    }

    @Test
    fun backupRoundTrip() = runBlocking {
        val source = Env("src")
        source.controller.start()
        val id = source.controller.add(com.graphite.torrent.core.model.AddRequest(
            com.graphite.torrent.core.model.AddSource.Magnet("magnet:?xt=urn:btih:c9e15763f722f23e98a29decdfae341b98d53056&dn=Kept"),
            category = "Movies", tags = listOf("4k"),
        ))
        source.meta.saveCategory(Category("Movies", "/movies"))
        source.settings.update { it.copy(profile = SpeedProfile.BALANCED, remoteKeyBase64 = "secret") }
        val sourceScheduler = SchedulerService(source.db, source.settings, source.controller, source.log, scope)
        sourceScheduler.save(ScheduleRule(days = 1, startMinute = 60, endMinute = 120))
        val exported = BackupService(source.engine, source.settings, source.meta, sourceScheduler, null, source.log).export()
        assertTrue(!exported.toString(Charsets.UTF_8).contains("secret"), "remote key is not exported")

        val target = Env("dst")
        target.controller.start()
        val targetScheduler = SchedulerService(target.db, target.settings, target.controller, target.log, scope)
        val restored = BackupService(target.engine, target.settings, target.meta, targetScheduler, null, target.log).import(exported)
        assertEquals(1, restored)
        assertEquals(SpeedProfile.BALANCED, target.settings.current().profile)
        assertEquals("Movies", target.meta.get(id)?.category)
        assertEquals(listOf("4k"), target.meta.tagsOf(id))
        assertEquals(1, targetScheduler.all().size)
        assertTrue(id in target.engine.state.value)
        source.controller.stop()
        target.controller.stop()
    }
}
