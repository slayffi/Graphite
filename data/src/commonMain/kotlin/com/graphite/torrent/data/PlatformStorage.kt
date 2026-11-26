package com.graphite.torrent.data

import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.settings.AppSettings
import java.io.File

/** What should happen to the files of a torrent that just completed. */
sealed interface CompletionResult {
    /** Leave the files where they are. */
    data object Keep : CompletionResult

    /** Ask libtorrent to move the storage to a real folder. */
    data class MoveTo(val path: String) : CompletionResult

    /**
     * The platform already copied the files elsewhere (Android SAF folder) and deleted the originals.
     * The torrent is removed from the engine and kept in the list as a completed archive entry.
     */
    data class Relocated(val location: String) : CompletionResult
}

/** Platform specific file system knowledge (Android scoped storage / SAF, desktop folders). */
interface PlatformStorage {
    /** Folder libtorrent writes into when the user did not choose one. */
    fun defaultSavePath(settings: AppSettings): String

    /** Converts a folder chosen in the UI (path or content URI) to a path libtorrent can write to. */
    fun resolveSavePath(chosen: String, settings: AppSettings): String = chosen.ifBlank { defaultSavePath(settings) }

    /** Free bytes on the volume of [path] (or its nearest existing parent). */
    fun freeSpace(path: String): Long {
        var f: File? = File(path)
        while (f != null && !f.exists()) f = f.parentFile
        return f?.usableSpace ?: 0L
    }

    /**
     * Makes sure libtorrent can write into [path] (creating it when needed) before a download is added.
     * Throws [ErrorKind.FOLDER_UNAVAILABLE] for missing drives, read-only folders and paths that point to a file.
     */
    fun ensureWritable(path: String) {
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, path)
        // canWrite() is unreliable for folders on Windows: probe with a real file.
        try {
            File.createTempFile(".graphite", ".probe", dir).delete()
        } catch (e: Exception) {
            throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, path, e)
        }
    }

    /** Called once when a torrent completes for the first time. */
    suspend fun afterCompletion(item: TorrentItem, files: List<FileEntry>, settings: AppSettings): CompletionResult {
        val target = settings.moveCompletedTo
        return if (target.isNotBlank() && File(target).isAbsolute) CompletionResult.MoveTo(target) else CompletionResult.Keep
    }

    /** Deletes files at a location produced by [CompletionResult.Relocated]. */
    suspend fun deleteRelocated(location: String, files: List<String>) {}
}

/** Plain file system storage used on desktop and in tests. */
open class FileSystemStorage(private val defaultDir: String) : PlatformStorage {
    override fun defaultSavePath(settings: AppSettings): String =
        settings.defaultSavePath.ifBlank { defaultDir }
}
