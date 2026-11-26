package com.graphite.torrent.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.graphite.torrent.BuildConfig
import com.graphite.torrent.core.net.BoundedIo
import com.graphite.torrent.ui.app.PickedFile
import com.graphite.torrent.ui.app.PlatformServices
import com.graphite.torrent.ui.designsystem.platform.PlatformKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Android implementation of the platform features; pickers go through the current [MainActivity]. */
class AndroidPlatformServices(
    private val context: Context,
    private val storage: AndroidStorage,
    private val bridge: ActivityBridge,
) : PlatformServices {
    override val kind = PlatformKind.ANDROID
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val canScanQr: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    override val supportsAllFilesAccess = true

    override suspend fun pickTorrentFiles(): List<PickedFile> {
        val uris = bridge.openDocuments(arrayOf("application/x-bittorrent", "application/octet-stream")) ?: return emptyList()
        return withContext(Dispatchers.IO) { uris.mapNotNull { readUri(it, BoundedIo.MAX_TORRENT_BYTES) } }
    }

    override suspend fun pickFolder(): String? {
        val uri = bridge.openTree() ?: return null
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        return uri.toString()
    }

    override suspend fun pickFile(mimeTypes: List<String>): PickedFile? {
        val uri = bridge.openDocuments(mimeTypes.toTypedArray(), single = true)?.firstOrNull() ?: return null
        return withContext(Dispatchers.IO) { readUri(uri, MAX_PICKED_BYTES) }
    }

    override suspend fun pickFilePath(mimeTypes: List<String>): String? {
        // libtorrent needs a readable file: copy the picked list into app storage.
        val picked = pickFile(mimeTypes) ?: return null
        return withContext(Dispatchers.IO) {
            // The name comes from another app's file provider: keep only harmless characters so that it cannot
            // point outside this folder ("../graphite/settings.json").
            val safeName = File(picked.name).name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').ifBlank { "list" }
            val target = File(File(context.filesDir, "ipfilter").apply { mkdirs() }, safeName)
            target.writeBytes(picked.bytes)
            target.absolutePath
        }
    }

    override suspend fun saveFile(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean {
        val uri = bridge.createDocument(suggestedName, mimeType) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
        }
    }

    /** Reads a document another app handed over. Bounded in size and time: the sender is not trusted. */
    private fun readUri(uri: Uri, limit: Int): PickedFile? = runCatching {
        // Only real documents, and never this app's own files (an intent could otherwise point at them).
        if (uri.scheme != "content" || uri.authority == "${context.packageName}.files") return null
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            BoundedIo.read(it, limit, System.currentTimeMillis() + READ_DEADLINE_MS)
        } ?: return null
        PickedFile(name, bytes)
    }.getOrNull()

    /** A .torrent opened from another app (browser download, file manager, "Share"). Never on the main thread. */
    suspend fun readIncoming(uri: Uri): PickedFile? = withContext(Dispatchers.IO) { readUri(uri, BoundedIo.MAX_TORRENT_BYTES) }

    override fun readClipboardText(): String? = runCatching {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

    override fun copyToClipboard(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Graphite", text))
    }

    override suspend fun scanQr(): String? = bridge.scanQr()

    override fun openFolder(path: String) {
        val intent = if (path.startsWith("content://")) {
            val tree = Uri.parse(path.substringBefore('#'))
            Intent(Intent.ACTION_VIEW).setDataAndType(tree, "vnd.android.document/directory")
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" +
                Uri.encode(path.removePrefix(android.os.Environment.getExternalStorageDirectory().absolutePath).trimStart('/'))), "vnd.android.document/directory")
        }
        startSafely(intent)
    }

    override fun openFile(path: String) {
        val uri = if (path.startsWith("content://")) Uri.parse(path) else {
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", File(path)) }.getOrNull() ?: return
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase()) ?: "*/*"
        startSafely(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun shareText(text: String) {
        startSafely(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    private fun startSafely(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }

    override fun haptic() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun displayPath(path: String): String = storage.displayPath(path)

    override fun hasAllFilesAccess(): Boolean = storage.hasAllFilesAccess()

    override fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                startSafely(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            bridge.requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            bridge.requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }

    private companion object {
        /** Backups and IP block lists picked by the user can be tens of megabytes. */
        const val MAX_PICKED_BYTES = 128 * 1024 * 1024
        const val READ_DEADLINE_MS = 60_000L
    }
}
