package com.graphite.torrent.data.backup

import com.graphite.torrent.core.engine.TorrentEngine
import com.graphite.torrent.core.model.Category
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.RssFeed
import com.graphite.torrent.core.model.RssRule
import com.graphite.torrent.core.model.ScheduleRule
import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.core.util.Base64Util
import com.graphite.torrent.data.automation.RssService
import com.graphite.torrent.data.automation.SchedulerService
import com.graphite.torrent.data.db.Torrent
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.repo.TorrentMetaRepository
import com.graphite.torrent.data.settings.GraphiteJson
import com.graphite.torrent.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
data class BackupTorrent(
    val id: String,
    val name: String,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val addedAt: Long = 0,
    val completedAt: Long = 0,
    val savePath: String = "",
    val finalLocation: String? = null,
    val totalSize: Long = 0,
    val source: String = "",
    /** libtorrent resume data (bencoded, includes the info dictionary), base64. */
    val resumeData: String? = null,
)

@Serializable
data class Backup(
    val format: String = FORMAT,
    val version: Int = 1,
    val createdAt: Long = 0,
    val settings: AppSettings,
    val torrents: List<BackupTorrent> = emptyList(),
    val categories: List<Category> = emptyList(),
    val schedule: List<ScheduleRule> = emptyList(),
    val rssFeeds: List<RssFeed> = emptyList(),
    val rssRules: List<RssRule> = emptyList(),
) {
    companion object {
        const val FORMAT = "graphite-backup"
    }
}

/** Export and import of settings and the download list as a single JSON file. */
class BackupService(
    private val engine: TorrentEngine,
    private val settings: SettingsRepository,
    private val meta: TorrentMetaRepository,
    private val scheduler: SchedulerService?,
    private val rss: RssService?,
    private val eventLog: EventLogRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun export(): ByteArray {
        engine.saveAllResumeData()
        val rows = meta.all()
        val torrents = rows.map { row ->
            BackupTorrent(
                id = row.id,
                name = row.name,
                category = row.category,
                tags = meta.tagsOf(row.id),
                addedAt = row.added_at,
                completedAt = row.completed_at,
                savePath = row.save_path,
                finalLocation = row.final_location,
                totalSize = row.total_size,
                source = row.source,
                resumeData = engine.resumeData(row.id)?.let(Base64Util::encode),
            )
        }
        val (feeds, rules) = rss?.exportFeeds() ?: (emptyList<RssFeed>() to emptyList())
        val backup = Backup(
            createdAt = clock(),
            // Remote-control keys stay on this device.
            settings = settings.current().copy(remoteKeyBase64 = "", remotePairing = null, remoteMode = false),
            torrents = torrents,
            categories = meta.categories.first(),
            schedule = scheduler?.all().orEmpty(),
            rssFeeds = feeds,
            rssRules = rules,
        )
        eventLog.log(EventType.SETTINGS_EXPORTED, torrents.size.toString())
        return GraphiteJson.encodeToString(Backup.serializer(), backup).toByteArray(Charsets.UTF_8)
    }

    /** Returns how many downloads were restored. */
    suspend fun import(bytes: ByteArray): Int {
        val backup = try {
            GraphiteJson.decodeFromString(Backup.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw GraphiteException(ErrorKind.INVALID_TORRENT, "not a Graphite backup", e)
        }
        if (backup.format != Backup.FORMAT) throw GraphiteException(ErrorKind.INVALID_TORRENT, "not a Graphite backup")
        val current = settings.current()
        settings.replace(
            backup.settings.copy(
                listenPort = current.listenPort,
                remoteKeyBase64 = current.remoteKeyBase64,
                remotePairing = current.remotePairing,
                remoteMode = current.remoteMode,
                firstRunDone = true,
            ),
        )
        backup.categories.forEach { meta.saveCategory(it) }
        if (backup.schedule.isNotEmpty()) scheduler?.replaceAll(backup.schedule)
        if (backup.rssFeeds.isNotEmpty() || backup.rssRules.isNotEmpty()) rss?.importFeeds(backup.rssFeeds, backup.rssRules)

        var restored = 0
        for (t in backup.torrents) {
            val data = t.resumeData?.let { runCatching { Base64Util.decode(it) }.getOrNull() }
            if (data != null) {
                val added = runCatching { engine.addFromResumeData(data) }
                if (added.isFailure && (added.exceptionOrNull() as? GraphiteException)?.error?.kind != ErrorKind.DUPLICATE) continue
            } else if (t.finalLocation == null) {
                continue
            }
            meta.restore(
                Torrent(t.id, t.name, t.category, t.addedAt, t.completedAt, t.savePath, t.finalLocation, t.totalSize, t.source),
                t.tags,
            )
            restored++
        }
        eventLog.log(EventType.SETTINGS_IMPORTED, restored.toString())
        return restored
    }
}
