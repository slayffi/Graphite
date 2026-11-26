package com.graphite.torrent.desktop

import com.graphite.torrent.ui.app.PickedFile
import com.graphite.torrent.ui.app.PlatformServices
import com.graphite.torrent.ui.designsystem.platform.PlatformKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser

/** Desktop implementation of platform features (native Windows dialogs, clipboard, Explorer). */
class DesktopPlatformServices(private val ownerProvider: () -> Frame?) : PlatformServices {
    override val kind = PlatformKind.DESKTOP
    override val appVersion: String = System.getProperty("graphite.version") ?: "1.0.0"
    override val canScanQr = false
    override val supportsAllFilesAccess = false

    private fun dialog(title: String, mode: Int): FileDialog = FileDialog(ownerProvider(), title, mode)

    override suspend fun pickTorrentFiles(): List<PickedFile> = withContext(Dispatchers.Swing) {
        val d = dialog("Graphite", FileDialog.LOAD)
        d.isMultipleMode = true
        d.file = "*.torrent"
        d.setFilenameFilter { _, name -> name.lowercase().endsWith(".torrent") }
        d.isVisible = true
        d.files.orEmpty().filter { it.isFile }.map { PickedFile(it.name, it.readBytes()) }
    }

    override suspend fun pickFolder(): String? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            currentDirectory = DesktopPaths.defaultDownloads
        }
        if (chooser.showOpenDialog(ownerProvider()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }

    override suspend fun pickFile(mimeTypes: List<String>): PickedFile? = withContext(Dispatchers.Swing) {
        val d = dialog("Graphite", FileDialog.LOAD)
        d.isVisible = true
        d.files.orEmpty().firstOrNull()?.takeIf { it.isFile }?.let { PickedFile(it.name, it.readBytes()) }
    }

    override suspend fun pickFilePath(mimeTypes: List<String>): String? = withContext(Dispatchers.Swing) {
        val d = dialog("Graphite", FileDialog.LOAD)
        d.isVisible = true
        d.files.orEmpty().firstOrNull()?.absolutePath
    }

    override suspend fun saveFile(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean {
        val target = withContext(Dispatchers.Swing) {
            val d = dialog("Graphite", FileDialog.SAVE)
            d.file = suggestedName
            d.isVisible = true
            d.files.orEmpty().firstOrNull()
        } ?: return false
        return withContext(Dispatchers.IO) { runCatching { target.writeBytes(bytes) }.isSuccess }
    }

    override fun readClipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun copyToClipboard(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override suspend fun scanQr(): String? = null

    override fun openFolder(path: String) {
        val f = File(path)
        runCatching {
            if (DesktopPaths.isWindows) {
                if (f.isFile) ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start()
                else ProcessBuilder("explorer.exe", f.absolutePath).start()
            } else {
                Desktop.getDesktop().open(if (f.isDirectory) f else f.parentFile)
            }
        }
    }

    override fun openFile(path: String) {
        runCatching { Desktop.getDesktop().open(File(path)) }
    }

    override fun shareText(text: String) = copyToClipboard(text)

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(java.net.URI(url)) }
    }

    override fun haptic() = Unit

    override fun displayPath(path: String): String = path

    override fun hasAllFilesAccess() = true
    override fun requestAllFilesAccess() = Unit
    override fun requestNotificationPermission() = Unit
}
