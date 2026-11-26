package com.graphite.torrent.data.backup

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
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
    /** Folder restored downloads may write into without being paused first. */
    private val defaultSavePath: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun export(): ByteArray = withContext(Dispatchers.IO) {
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
            // Secrets stay on this device: the remote-control key and pairing, and the proxy password.
            settings = settings.current().let {
                it.copy(remoteKeyBase64 = "", remotePairing = null, remoteMode = false, proxy = it.proxy.copy(password = ""))
            },
            torrents = torrents,
            categories = meta.categories.first(),
            schedule = scheduler?.all().orEmpty(),
            rssFeeds = feeds,
            rssRules = rules,
        )
        eventLog.log(EventType.SETTINGS_EXPORTED, torrents.size.toString())
        GraphiteJson.encodeToString(Backup.serializer(), backup).toByteArray(Charsets.UTF_8)
    }

    /** Returns how many downloads were restored. */
    suspend fun import(bytes: ByteArray): Int = withContext(Dispatchers.IO) {
        val backup = try {
            GraphiteJson.decodeFromString(Backup.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw GraphiteException(ErrorKind.INVALID_TORRENT, "not a Graphite backup", e)
        }
        if (backup.format != Backup.FORMAT) throw GraphiteException(ErrorKind.INVALID_TORRENT, "not a Graphite backup")
        val current = settings.current()
        val imported = backup.settings
        settings.replace(
            imported.copy(
                listenPort = current.listenPort,
                // Everything about remote control stays as it is here: a file must not be able to open the port.
                remoteServerEnabled = current.remoteServerEnabled,
                remotePort = current.remotePort,
                remoteKeyBase64 = current.remoteKeyBase64,
                remotePairing = current.remotePairing,
                remoteMode = current.remoteMode,
                // Folders are taken from the file only when they exist on this device (and are not network shares).
                defaultSavePath = localFolderOr(imported.defaultSavePath, current.defaultSavePath),
                moveCompletedTo = localFolderOr(imported.moveCompletedTo, current.moveCompletedTo),
                watchFolderPath = localFolderOr(imported.watchFolderPath, current.watchFolderPath),
                watchFolderEnabled = imported.watchFolderEnabled && localFolderOr(imported.watchFolderPath, "").isNotBlank(),
                ipFilterPath = current.ipFilterPath,
                ipFilterEnabled = current.ipFilterEnabled,
                // The exported file carries no proxy password; keep ours when it is the same proxy.
                proxy = if (imported.proxy.password.isBlank() && imported.proxy.host == current.proxy.host) {
                    imported.proxy.copy(password = current.proxy.password)
                } else {
                    imported.proxy
                },
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
                val added = runCatching { engine.addFromResumeData(data, defaultSavePath()) }
                if (added.isFailure && (added.exceptionOrNull() as? GraphiteException)?.error?.kind != ErrorKind.DUPLICATE) continue
            } else if (t.finalLocation == null) {
                continue
            }
            // Only Android folder locations are kept as an archive entry; they are shown, never deleted from here.
            val finalLocation = t.finalLocation?.takeIf { it.startsWith("content://") }
            if (data == null && finalLocation == null) continue
            meta.restore(
                Torrent(t.id, t.name, t.category, t.addedAt, t.completedAt, t.savePath, finalLocation, t.totalSize, t.source),
                t.tags,
            )
            restored++
        }
        eventLog.log(EventType.SETTINGS_IMPORTED, restored.toString())
        restored
    }

    /** [candidate] when it is an existing local folder or an Android folder URI, otherwise [fallback]. */
    private fun localFolderOr(candidate: String, fallback: String): String = when {
        candidate.isBlank() -> candidate
        candidate.startsWith("content://") -> candidate
        candidate.startsWith("\\\\") || candidate.startsWith("//") -> fallback
        File(candidate).let { it.isAbsolute && it.isDirectory } -> candidate
        else -> fallback
    }
}
