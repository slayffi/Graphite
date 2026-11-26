package com.graphite.torrent.data.automation

import com.graphite.torrent.core.net.BoundedIo
import com.graphite.torrent.core.controller.TorrentController
import com.graphite.torrent.core.engine.ErrorMapper
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.AddSource
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.settings.WatchFolderAction
import com.graphite.torrent.core.util.Base64Util
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A .torrent file found in the watched folder. */
data class WatchedFile(val name: String, val handle: String)

/** Lists and consumes files of a watched folder (plain folder, or a SAF tree on Android). */
interface WatchFolderSource {
    fun supports(location: String): Boolean
    fun list(location: String): List<WatchedFile>
    fun read(file: WatchedFile): ByteArray
    /** Renames the file to "*.added" / "*.invalid" or deletes it so it is not added twice. */
    fun finish(file: WatchedFile, action: WatchFolderAction, success: Boolean)
}

class FileWatchFolderSource : WatchFolderSource {
    override fun supports(location: String) = !location.startsWith("content://")

    override fun list(location: String): List<WatchedFile> =
        File(location).listFiles { f -> f.isFile && f.name.lowercase().endsWith(".torrent") }
            ?.sortedBy { it.lastModified() }
            ?.map { WatchedFile(it.name, it.absolutePath) }
            .orEmpty()

    // A huge "something.torrent" (the watched folder is often the browser's download folder) must be marked
    // invalid, not read into memory again every few seconds.
    override fun read(file: WatchedFile): ByteArray = BoundedIo.readFile(File(file.handle), BoundedIo.MAX_TORRENT_BYTES)

    override fun finish(file: WatchedFile, action: WatchFolderAction, success: Boolean) {
        val f = File(file.handle)
        if (success && action == WatchFolderAction.DELETE) {
            f.delete()
        } else {
            val suffix = if (success) ".added" else ".invalid"
            val target = File(f.parentFile, f.name + suffix)
            if (!f.renameTo(target)) f.delete()
        }
    }
}

/** Polls the watched folder and adds new .torrent files automatically. */
class WatchFolderService(
    private val settings: SettingsRepository,
    private val controller: TorrentController,
    private val eventLog: EventLogRepository,
    private val scope: CoroutineScope,
    private val sources: List<WatchFolderSource> = listOf(FileWatchFolderSource()),
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                runCatching { scanOnce() }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Returns how many torrents were added. */
    suspend fun scanOnce(): Int {
        val s = settings.current()
        if (!s.watchFolderEnabled || s.watchFolderPath.isBlank()) return 0
        val source = sources.firstOrNull { it.supports(s.watchFolderPath) } ?: return 0
        val files = withContext(Dispatchers.IO) { runCatching { source.list(s.watchFolderPath) }.getOrDefault(emptyList()) }
        var added = 0
        for (file in files) {
            val ok = try {
                val bytes = withContext(Dispatchers.IO) { source.read(file) }
                controller.add(AddRequest(AddSource.TorrentFile(Base64Util.encode(bytes))))
                eventLog.log(EventType.WATCH_FOLDER_ADDED, file.name)
                added++
                true
            } catch (e: GraphiteException) {
                // An already added torrent counts as handled.
                if (e.error.kind != ErrorKind.DUPLICATE) eventLog.error(EventType.WATCH_FOLDER_FAILED, file.name, e.error)
                e.error.kind == ErrorKind.DUPLICATE
            } catch (e: Exception) {
                eventLog.error(EventType.WATCH_FOLDER_FAILED, file.name, ErrorMapper.fromException(e))
                false
            }
            withContext(Dispatchers.IO) { runCatching { source.finish(file, s.watchFolderAction, ok) } }
        }
        return added
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}
