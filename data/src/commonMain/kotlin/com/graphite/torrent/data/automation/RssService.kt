package com.graphite.torrent.data.automation

import com.graphite.torrent.core.net.SafeHttp
import com.graphite.torrent.core.net.BoundedIo
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.graphite.torrent.core.controller.TorrentController
import com.graphite.torrent.core.engine.ErrorMapper
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.AddSource
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.RssEntry
import com.graphite.torrent.core.model.RssFeed
import com.graphite.torrent.core.model.RssRule
import com.graphite.torrent.core.rss.RssMatcher
import com.graphite.torrent.core.rss.RssParser
import com.graphite.torrent.core.util.Base64Util
import com.graphite.torrent.data.db.GraphiteDatabase
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI

/** RSS feeds with rules that download matching items automatically. */
class RssService(
    private val db: GraphiteDatabase,
    private val settings: SettingsRepository,
    private val controller: TorrentController,
    private val eventLog: EventLogRepository,
    private val scope: CoroutineScope,
    /** Replaces the network in tests; null = real HTTP with size, time and address limits. */
    private val fetcher: ((String) -> ByteArray)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val q get() = db.rssQueries
    private val mutex = Mutex()
    private var job: Job? = null

    val feeds: Flow<List<RssFeed>> = q.selectFeeds().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { RssFeed(it.id, it.url, it.title, it.enabled, it.last_checked, it.last_error) }
    }

    val rules: Flow<List<RssRule>> = q.selectRules().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { RssRule(it.id, it.name, it.feed_id, it.include_pattern, it.exclude_pattern, it.use_regex, it.category, it.save_path, it.start_paused, it.enabled) }
    }

    val recentItems: Flow<List<RssEntry>> = q.selectRecentItems(200).asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { RssEntry(it.feed_id, it.guid, it.title, it.link, it.published, it.size, it.downloaded) }
    }

    suspend fun addFeed(url: String): Long = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) throw GraphiteException(com.graphite.torrent.core.model.ErrorKind.INVALID_TORRENT, clean)
        q.insertFeed(clean, "", true)
        val id = q.selectFeeds().executeAsList().first { it.url == clean }.id
        // First fetch only remembers existing items so old episodes are not downloaded.
        runCatching { refreshFeed(id, clean, downloadNew = false) }
        id
    }

    suspend fun setFeedEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) { q.setFeedEnabled(enabled, id) }

    suspend fun deleteFeed(id: Long) = withContext(Dispatchers.IO) {
        db.transaction {
            q.deleteItemsOfFeed(id)
            q.deleteFeed(id)
        }
    }

    suspend fun saveRule(rule: RssRule) = withContext(Dispatchers.IO) {
        if (rule.id == 0L) {
            q.insertRule(rule.name, rule.feedId, rule.include, rule.exclude, rule.useRegex, rule.category, rule.savePath, rule.startPaused, rule.enabled)
        } else {
            q.updateRule(rule.name, rule.feedId, rule.include, rule.exclude, rule.useRegex, rule.category, rule.savePath, rule.startPaused, rule.enabled, rule.id)
        }
    }

    suspend fun deleteRule(id: Long) = withContext(Dispatchers.IO) { q.deleteRule(id) }

    fun start() {
        if (job != null) return
        job = scope.launch {
            delay(15_000)
            while (isActive) {
                val s = settings.current()
                if (s.rssEnabled) runCatching { refreshAll() }
                delay(s.rssRefreshMinutes.coerceAtLeast(5) * 60_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Refreshes every enabled feed now. Returns the number of downloads started. */
    suspend fun refreshAll(): Int = mutex.withLock {
        var started = 0
        val feeds = withContext(Dispatchers.IO) { q.selectFeeds().executeAsList() }
        for (feed in feeds.filter { it.enabled }) started += refreshFeed(feed.id, feed.url, downloadNew = true)
        started
    }

    private suspend fun refreshFeed(id: Long, url: String, downloadNew: Boolean): Int {
        val parsed = try {
            withContext(Dispatchers.IO) { RssParser.parse(fetchFeed(url)) }
        } catch (e: Exception) {
            val error = ErrorMapper.fromException(e)
            withContext(Dispatchers.IO) { q.markFeedChecked(clock(), error.detail, id) }
            eventLog.error(EventType.RSS_FAILED, url, error)
            return 0
        }
        val rules = withContext(Dispatchers.IO) { q.selectRules().executeAsList() }
            .map { RssRule(it.id, it.name, it.feed_id, it.include_pattern, it.exclude_pattern, it.use_regex, it.category, it.save_path, it.start_paused, it.enabled) }
            .filter { it.enabled && (it.feedId == null || it.feedId == id) }
        var started = 0
        withContext(Dispatchers.IO) {
            if (parsed.title.isNotBlank()) q.updateFeedTitle(parsed.title, id)
            q.markFeedChecked(clock(), "", id)
        }
        for (item in parsed.items) {
            val isNew = withContext(Dispatchers.IO) { q.itemExists(id, item.guid).executeAsOne() == 0L }
            if (!isNew) continue
            withContext(Dispatchers.IO) { q.insertItem(id, item.guid, item.title, item.link, item.published, item.size, false, clock()) }
            if (!downloadNew) continue
            val rule = rules.firstOrNull { RssMatcher.matches(it, item.title) } ?: continue
            try {
                val source = if (item.link.startsWith("magnet:")) {
                    AddSource.Magnet(item.link)
                } else {
                    AddSource.TorrentFile(Base64Util.encode(withContext(Dispatchers.IO) { fetchTorrent(item.link, feedUrl = url) }))
                }
                controller.add(AddRequest(source, savePath = rule.savePath.ifBlank { null }, startPaused = rule.startPaused, category = rule.category))
                withContext(Dispatchers.IO) { q.markItemDownloaded(id, item.guid) }
                eventLog.log(EventType.RSS_DOWNLOADED, item.title)
                started++
            } catch (e: Exception) {
                eventLog.error(EventType.RSS_FAILED, item.title, ErrorMapper.fromException(e))
            }
        }
        withContext(Dispatchers.IO) { q.trimItems(id, id, 500) }
        return started
    }

    suspend fun exportFeeds(): Pair<List<RssFeed>, List<RssRule>> = withContext(Dispatchers.IO) {
        q.selectFeeds().executeAsList().map { RssFeed(it.id, it.url, it.title, it.enabled, it.last_checked, it.last_error) } to
            q.selectRules().executeAsList().map { RssRule(it.id, it.name, it.feed_id, it.include_pattern, it.exclude_pattern, it.use_regex, it.category, it.save_path, it.start_paused, it.enabled) }
    }

    suspend fun importFeeds(feeds: List<RssFeed>, rules: List<RssRule>) = withContext(Dispatchers.IO) {
        val idMap = HashMap<Long, Long>()
        for (f in feeds) {
            q.insertFeed(f.url, f.title, f.enabled)
            idMap[f.id] = q.selectFeeds().executeAsList().first { it.url == f.url }.id
        }
        for (r in rules) {
            q.insertRule(r.name, r.feedId?.let { idMap[it] }, r.include, r.exclude, r.useRegex, r.category, r.savePath, r.startPaused, r.enabled)
        }
        // Like a feed added by hand: what is in the feed right now is only remembered, not downloaded.
        for (f in feeds) {
            val id = idMap[f.id] ?: continue
            runCatching { mutex.withLock { refreshFeed(id, f.url, downloadNew = false) } }
        }
    }

    /** The feed address is typed in by the user, so a tracker proxy in the local network (Jackett, Prowlarr) is fine. */
    private fun fetchFeed(url: String): ByteArray =
        fetcher?.invoke(url) ?: SafeHttp.get(url, MAX_FEED_BYTES, allowPrivateHosts = true)

    /**
     * Links inside a feed are written by the feed: they may only point into the local network when the feed
     * itself lives there, otherwise a public feed could make Graphite send requests to the router or the PC.
     */
    private fun fetchTorrent(link: String, feedUrl: String): ByteArray {
        fetcher?.let { return it(link) }
        val feedIsLocal = runCatching { SafeHttp.isPrivateHost(URI(feedUrl).host.orEmpty()) }.getOrDefault(false)
        return SafeHttp.get(link, BoundedIo.MAX_TORRENT_BYTES, allowPrivateHosts = feedIsLocal)
    }

    private companion object {
        const val MAX_FEED_BYTES = 8 * 1024 * 1024
    }
}
