package com.graphite.torrent.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val _relocationProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val relocationProgress: StateFlow<Map<String, Float>> = _relocationProgress.asStateFlow()

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
        val uri = Uri.parse(treeUri)
        // Only the system's own storage provider maps to real paths, and never through "..".
        if (uri.authority != "com.android.externalstorage.documents") return@runCatching null
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (docId.split('/', ':').any { it == ".." }) return@runCatching null
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

    /** The SAF folder a download in [savePath] has to end up in, or null when it stays where it is. */
    private fun treeTarget(savePath: String, settings: AppSettings): String? {
        val key = savePath.substringAfter("staging-", "").substringBefore('/').takeIf { savePath.contains("/staging-") }
        return key?.let { prefs.getString(it, null) } ?: settings.moveCompletedTo.takeIf { it.startsWith("content://") }
    }

    override fun needsRelocation(item: TorrentItem, settings: AppSettings): Boolean {
        val target = treeTarget(item.savePath, settings) ?: return false
        return !(hasAllFilesAccess() && treeToPath(target) == item.savePath)
    }

    override suspend fun afterCompletion(item: TorrentItem, files: List<FileEntry>, settings: AppSettings): CompletionResult {
        val savePath = item.savePath
        val target = treeTarget(savePath, settings) ?: return when {
            settings.moveCompletedTo.isNotBlank() -> CompletionResult.MoveTo(settings.moveCompletedTo)
            else -> CompletionResult.Keep
        }
        if (hasAllFilesAccess()) {
            treeToPath(target)?.let { path -> return if (path != savePath) CompletionResult.MoveTo(path) else CompletionResult.Keep }
        }
        return withContext(Dispatchers.IO) { copyToTree(item, files, target) }
    }

    /**
     * Copies a finished download from the staging folder into the chosen folder.
     *
     * Nothing that already exists in the user's folder is ever deleted or merged into: the download gets a
     * folder (or file) of its own, which the system renames ("Name (1)") when the name is taken. Only entries
     * Graphite created itself are remembered and may later be removed by "delete with files".
     */
    private fun copyToTree(item: TorrentItem, files: List<FileEntry>, treeUri: String): CompletionResult {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, treeUri)
        if (!tree.canWrite()) throw GraphiteException(ErrorKind.PERMISSION_DENIED, treeUri)
        val wanted = files.filter { it.priority != FilePriority.SKIP }
        val needed = wanted.sumOf { it.size }
        val free = runCatching { treeToPath(treeUri)?.let { StatFs(it).availableBytes } }.getOrNull()
        if (free != null && free < needed) throw GraphiteException(ErrorKind.NO_SPACE, "need $needed, free $free")

        val base = File(item.savePath)
        val root = files.firstOrNull()?.path?.substringBefore('/')?.takeIf { r -> r.isNotEmpty() && files.all { it.path.startsWith("$r/") } }
        val pendingKey = "pending:${item.id}"
        var copied = 0L
        fun progress(delta: Long) {
            copied += delta
            if (needed > 0) _relocationProgress.update { it + (item.id to (copied.toDouble() / needed).toFloat().coerceIn(0f, 1f)) }
        }
        progress(0)
        try {
            val createdName: String
            if (root != null) {
                // An interrupted earlier attempt left a folder of ours: continue in it instead of making "Name (2)".
                val previous = prefs.getString(pendingKey, null)?.let { tree.findFile(it) }?.takeIf { it.isDirectory }
                val rootDoc = previous ?: tree.createDirectory(root) ?: throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, root)
                createdName = rootDoc.name ?: root
                prefs.edit().putString(pendingKey, createdName).apply()
                for (f in wanted) {
                    val source = File(base, f.path)
                    if (!source.isFile) continue
                    val parts = f.path.split('/').filter { it.isNotEmpty() }.drop(1)
                    var dir: DocumentFile = rootDoc
                    for (folder in parts.dropLast(1)) {
                        val existing = dir.findFile(folder)
                        dir = when {
                            existing == null -> dir.createDirectory(folder) ?: throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, folder)
                            existing.isDirectory -> existing
                            else -> throw GraphiteException(ErrorKind.FILE_IO, folder)
                        }
                    }
                    val name = parts.last()
                    // Inside our own folder a leftover from the interrupted attempt is replaced.
                    dir.findFile(name)?.let { if (it.isDirectory) throw GraphiteException(ErrorKind.FILE_IO, name) else it.delete() }
                    copyFile(source, dir, name, ::progress)
                }
            } else {
                val single = wanted.singleOrNull()
                    ?: throw GraphiteException(ErrorKind.FILE_IO, "torrent without a root folder: ${item.name}")
                val source = File(base, single.path)
                // Remove only what an interrupted attempt of ours created, never a file that was already there.
                prefs.getString(pendingKey, null)?.let { tree.findFile(it) }?.takeIf { it.isFile }?.delete()
                val doc = copyFile(source, tree, single.name) { delta ->
                    progress(delta)
                }
                createdName = doc.name ?: single.name
                prefs.edit().putString(pendingKey, createdName).apply()
            }
            // Copy finished: remove the staged originals.
            for (f in files) File(base, f.path).delete()
            root?.let { File(base, it).deleteRecursively() }
            val location = joinLocation(treeUri, createdName)
            prefs.edit().remove(pendingKey).putStringSet(CREATED, created() + location).apply()
            return CompletionResult.Relocated(location)
        } finally {
            _relocationProgress.update { it - item.id }
        }
    }

    private fun copyFile(source: File, dir: DocumentFile, name: String, onCopied: (Long) -> Unit): DocumentFile {
        // "application/octet-stream" keeps the name exactly as it is (other types make the system append extensions).
        val doc = dir.createFile("application/octet-stream", name) ?: throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, name)
        val out = context.contentResolver.openOutputStream(doc.uri) ?: throw GraphiteException(ErrorKind.FILE_IO, name)
        out.use { target ->
            source.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    target.write(buffer, 0, n)
                    onCopied(n.toLong())
                }
            }
        }
        return doc
    }

    private fun created(): Set<String> = prefs.getStringSet(CREATED, emptySet()).orEmpty()

    override suspend fun deleteRelocated(location: String, files: List<String>) = withContext(Dispatchers.IO) {
        // Only folders and files Graphite put there itself: a location from an imported backup, or a folder the
        // user already had, is never deleted.
        if (location !in created()) return@withContext
        val (tree, root) = splitLocation(location)
        if (root.isEmpty()) return@withContext
        val doc = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return@withContext
        doc.findFile(root)?.delete()
        prefs.edit().putStringSet(CREATED, created() - location).apply()
    }

    private fun joinLocation(tree: String, root: String) = if (root.isEmpty()) tree else "$tree#$root"

    private fun splitLocation(location: String): Pair<String, String> =
        location.substringBefore('#') to location.substringAfter('#', "")

    private companion object {
        const val CREATED = "created"
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
