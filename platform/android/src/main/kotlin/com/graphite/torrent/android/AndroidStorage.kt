package com.graphite.torrent.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.data.CompletionResult
import com.graphite.torrent.data.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Hybrid storage for Android.
 *
 * libtorrent can only write to real file paths. With "All files access" Graphite downloads straight
 * into the chosen folder. Without it, files go to the app's own external storage (no permission
 * needed) into a sub-folder that remembers the target SAF folder; after completion they are copied
 * there through the Storage Access Framework and the originals are deleted.
 */
class AndroidStorage(private val context: Context) : PlatformStorage {
    private val prefs = context.getSharedPreferences("saf_targets", Context.MODE_PRIVATE)

    private val internalRoot: File
        get() = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")).apply { mkdirs() }

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    override fun defaultSavePath(settings: AppSettings): String {
        val tree = settings.androidTreeUri
        if (tree.isNotBlank()) return resolveTree(tree)
        if (settings.defaultSavePath.isNotBlank() && hasAllFilesAccess() && File(settings.defaultSavePath).isAbsolute) {
            return settings.defaultSavePath
        }
        return internalRoot.absolutePath
    }

    override fun resolveSavePath(chosen: String, settings: AppSettings): String = when {
        chosen.isBlank() -> defaultSavePath(settings)
        chosen.startsWith("content://") -> resolveTree(chosen)
        else -> chosen
    }

    /** Real path for a SAF tree: direct path with all-files access, otherwise a staging folder bound to it. */
    private fun resolveTree(treeUri: String): String {
        if (hasAllFilesAccess()) treeToPath(treeUri)?.let { return File(it).apply { mkdirs() }.absolutePath }
        val key = sha1(treeUri).take(12)
        prefs.edit().putString(key, treeUri).apply()
        return File(internalRoot, "staging-$key").apply { mkdirs() }.absolutePath
    }

    /** "content://…/tree/primary%3ADownload%2FGraphite" → "/storage/emulated/0/Download/Graphite". */
    fun treeToPath(treeUri: String): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        if (relative.isEmpty()) base else "$base/$relative"
    }.getOrNull()

    fun displayPath(path: String): String {
        if (path.startsWith("content://")) {
            val (tree, root) = splitLocation(path)
            val base = treeToPath(tree) ?: tree
            return if (root.isEmpty()) base else "$base/$root"
        }
        val internal = internalRoot.absolutePath
        if (path.startsWith(internal)) {
            val key = path.substringAfter("staging-", "").substringBefore('/')
            val target = prefs.getString(key, null)
            if (target != null) return displayPath(target)
        }
        return path
    }

    override fun freeSpace(path: String): Long {
        var f: File? = File(path)
        while (f != null && !f.exists()) f = f.parentFile
        return runCatching { StatFs((f ?: internalRoot).absolutePath).availableBytes }.getOrDefault(0L)
    }

    override suspend fun afterCompletion(item: TorrentItem, files: List<FileEntry>, settings: AppSettings): CompletionResult {
        val savePath = item.savePath
        val stagingKey = savePath.substringAfter("staging-", "").substringBefore('/').takeIf { savePath.contains("/staging-") }
        val stagingTarget = stagingKey?.let { prefs.getString(it, null) }
        val target = when {
            stagingTarget != null -> stagingTarget
            settings.moveCompletedTo.startsWith("content://") -> settings.moveCompletedTo
            settings.moveCompletedTo.isNotBlank() -> return CompletionResult.MoveTo(settings.moveCompletedTo)
            else -> return CompletionResult.Keep
        }
        if (hasAllFilesAccess()) {
            treeToPath(target)?.let { path -> if (path != savePath) return CompletionResult.MoveTo(path) else return CompletionResult.Keep }
        }
        return withContext(Dispatchers.IO) { copyToTree(item, files, target) }
    }

    private fun copyToTree(item: TorrentItem, files: List<FileEntry>, treeUri: String): CompletionResult {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, treeUri)
        if (!tree.canWrite()) throw GraphiteException(ErrorKind.PERMISSION_DENIED, treeUri)
        val wanted = files.filter { it.priority != FilePriority.SKIP }
        val needed = wanted.sumOf { it.size }
        val free = runCatching { treeToPath(treeUri)?.let { StatFs(it).availableBytes } }.getOrNull()
        if (free != null && free < needed) throw GraphiteException(ErrorKind.NO_SPACE, "need $needed, free $free")

        val base = File(item.savePath)
        for (f in wanted) {
            val source = File(base, f.path)
            if (!source.isFile) continue
            val parts = f.path.split('/').filter { it.isNotEmpty() }
            var dir: DocumentFile = tree
            for (folder in parts.dropLast(1)) {
                dir = dir.findFile(folder)?.takeIf { it.isDirectory } ?: dir.createDirectory(folder)
                    ?: throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, folder)
            }
            val name = parts.last()
            dir.findFile(name)?.delete()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            val doc = dir.createFile(mime, name) ?: throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, name)
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out, 1 shl 16) }
            } ?: throw GraphiteException(ErrorKind.FILE_IO, name)
        }
        // Copy finished: remove the staged originals.
        for (f in files) File(base, f.path).delete()
        val root = files.firstOrNull()?.path?.substringBefore('/')?.takeIf { files.all { f -> f.path.startsWith("$it/") } }
        root?.let { File(base, it).deleteRecursively() }
        return CompletionResult.Relocated(joinLocation(treeUri, root ?: files.singleOrNull()?.path.orEmpty()))
    }

    override suspend fun deleteRelocated(location: String, files: List<String>) = withContext(Dispatchers.IO) {
        val (tree, root) = splitLocation(location)
        if (root.isEmpty()) return@withContext
        val doc = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return@withContext
        doc.findFile(root)?.delete()
        Unit
    }

    private fun joinLocation(tree: String, root: String) = if (root.isEmpty()) tree else "$tree#$root"

    private fun splitLocation(location: String): Pair<String, String> =
        location.substringBefore('#') to location.substringAfter('#', "")

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
