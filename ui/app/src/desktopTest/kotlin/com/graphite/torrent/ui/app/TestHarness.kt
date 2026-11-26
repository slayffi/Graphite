package com.graphite.torrent.ui.app

import com.graphite.torrent.core.net.NetInterface
import com.graphite.torrent.data.DatabaseFactory
import com.graphite.torrent.data.FileSystemStorage
import com.graphite.torrent.data.LocalTorrentController
import com.graphite.torrent.data.db.GraphiteDatabase
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.repo.HistoryRepository
import com.graphite.torrent.data.repo.StatsRepository
import com.graphite.torrent.data.repo.TorrentMetaRepository
import com.graphite.torrent.data.settings.SettingsRepository
import com.graphite.torrent.testing.FakeTorrentEngine
import com.graphite.torrent.ui.designsystem.platform.PlatformKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/** Real controller, repositories and settings on top of [FakeTorrentEngine] and an in-memory database. */
class TestHarness(private val freeSpace: Long? = null) {
    val dir: File = Files.createTempDirectory("graphite-ui").toFile()
    val downloads = File(dir, "downloads").apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = FakeTorrentEngine()
    private val db = GraphiteDatabase(DatabaseFactory.createInMemory())
    val settings = SettingsRepository.create(File(dir, "settings.json").absolutePath, scope)
    val meta = TorrentMetaRepository(db)
    val stats = StatsRepository(db)
    val history = HistoryRepository(db)
    val eventLog = EventLogRepository(db)
    private val storage = object : FileSystemStorage(downloads.absolutePath) {
        override fun freeSpace(path: String): Long = this@TestHarness.freeSpace ?: super.freeSpace(path)
    }
    val controller = LocalTorrentController(engine, settings, meta, stats, eventLog, history, storage, scope)
    val platform = FakePlatformServices()
    val env = AppEnvironment(controller, controller, settings, meta, stats, history, eventLog, platform)

    fun start() = runBlocking {
        // The first-run effect would otherwise ask for permissions.
        settings.update { it.copy(firstRunDone = true) }
        controller.start()
    }

    fun close() {
        runBlocking { controller.stop() }
        scope.cancel()
        dir.deleteRecursively()
    }
}

/** Platform services without dialogs: pickers return nothing, the clipboard is a field. */
class FakePlatformServices : PlatformServices {
    override val kind = PlatformKind.DESKTOP
    override val appVersion = "test"
    var clipboard: String? = null
    val opened = ArrayList<String>()

    override suspend fun pickTorrentFiles(): List<PickedFile> = emptyList()
    override suspend fun pickFolder(): String? = null
    override suspend fun pickFile(mimeTypes: List<String>): PickedFile? = null
    override suspend fun pickFilePath(mimeTypes: List<String>): String? = null
    override suspend fun saveFile(suggestedName: String, mimeType: String, bytes: ByteArray) = false
    override fun readClipboardText(): String? = clipboard
    override fun copyToClipboard(text: String) { clipboard = text }
    override val canScanQr = false
    override suspend fun scanQr(): String? = null
    override fun openFolder(path: String) { opened += path }
    override fun openFile(path: String) { opened += path }
    override fun shareText(text: String) = Unit
    override fun haptic() = Unit
    override fun displayPath(path: String) = path
    override val supportsAllFilesAccess = false
    override fun hasAllFilesAccess() = true
    override fun requestAllFilesAccess() = Unit
    override fun requestNotificationPermission() = Unit
    override fun networkInterfaces(): List<NetInterface> = emptyList()
}
