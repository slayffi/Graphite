package com.graphite.torrent.ui.app

import com.graphite.torrent.core.controller.TorrentController
import com.graphite.torrent.data.LocalTorrentController
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.repo.HistoryRepository
import com.graphite.torrent.data.repo.StatsRepository
import com.graphite.torrent.data.repo.TorrentMetaRepository
import com.graphite.torrent.data.settings.SettingsRepository
import com.graphite.torrent.ui.designsystem.platform.PlatformKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A file picked by the user. */
class PickedFile(val name: String, val bytes: ByteArray)

/** Something the app was asked to open from outside: a magnet link, a .torrent file or shared text. */
sealed interface IncomingRequest {
    data class Magnet(val uri: String) : IncomingRequest
    data class TorrentFile(val name: String, val bytes: ByteArray) : IncomingRequest
    data class Text(val text: String) : IncomingRequest
}

/** Features that only the platform can provide. Implemented in platform:android and platform:desktop. */
interface PlatformServices {
    val kind: PlatformKind
    val appVersion: String

    suspend fun pickTorrentFiles(): List<PickedFile>

    /** Returns a folder the engine can use (path) or a SAF tree URI on Android. */
    suspend fun pickFolder(): String?

    /** Picks any file and returns its bytes (settings import, IP filter). */
    suspend fun pickFile(mimeTypes: List<String>): PickedFile?

    /** Picks a file path to read later (IP filter list); null when not supported. */
    suspend fun pickFilePath(mimeTypes: List<String>): String?

    /** Saves bytes chosen by the user (export). Returns true on success. */
    suspend fun saveFile(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean

    fun readClipboardText(): String?
    fun copyToClipboard(text: String)

    val canScanQr: Boolean
    suspend fun scanQr(): String?

    fun openFolder(path: String)
    fun openFile(path: String)
    fun shareText(text: String)

    /** Short vibration (Android) when a download completes. */
    fun haptic()

    /** Human-readable form of a path or content URI. */
    fun displayPath(path: String): String

    /** Android "All files access" (MANAGE_EXTERNAL_STORAGE). Always false/unsupported on desktop. */
    val supportsAllFilesAccess: Boolean
    fun hasAllFilesAccess(): Boolean
    fun requestAllFilesAccess()

    /** Android 13+ runtime notification permission. */
    fun requestNotificationPermission()

    /** Network interfaces the user can bind to (VPN). */
    fun networkInterfaces(): List<com.graphite.torrent.core.net.NetInterface> =
        com.graphite.torrent.core.net.NetworkInterfaces.list()

    /** Opens the platform's system settings page for this app (battery optimization, permissions). */
    fun openAppSettings() {}

    /** Opens a URL (stream link) in an external app. */
    fun openUrl(url: String) {}
}

/** Everything the screens need. Built by each platform from [com.graphite.torrent.data.GraphiteServices]. */
class AppEnvironment(
    val controller: TorrentController,
    val local: LocalTorrentController,
    val settings: SettingsRepository,
    val meta: TorrentMetaRepository,
    val stats: StatsRepository,
    val history: HistoryRepository,
    val eventLog: EventLogRepository,
    val platform: PlatformServices,
    /** Features registered by the platform module or stage-4 services (streaming, remote, RSS...). */
    val features: AppFeatures = AppFeatures(),
) {
    private val _incoming = MutableSharedFlow<IncomingRequest>(replay = 1, extraBufferCapacity = 16)
    val incoming: SharedFlow<IncomingRequest> = _incoming.asSharedFlow()

    /** Pending dialog requests from outside the UI tree (tray "Add", hotkeys). */
    val openAddSheet = MutableStateFlow(false)

    fun submit(request: IncomingRequest) {
        _incoming.tryEmit(request)
    }

    fun consumeIncoming() {
        _incoming.resetReplayCache()
    }
}

/**
 * Optional features provided by the platform layer. The UI hides entries whose feature is absent,
 * so each platform only shows what it supports.
 */
class AppFeatures(
    val streaming: StreamingFeature? = null,
    val remote: RemoteFeature? = null,
    val rss: com.graphite.torrent.data.automation.RssService? = null,
    val scheduler: com.graphite.torrent.data.automation.SchedulerService? = null,
    val backup: com.graphite.torrent.data.backup.BackupService? = null,
    val desktop: DesktopFeature? = null,
)

/** Streaming / built-in player. */
interface StreamingFeature {
    /** Returns a URL the player can open while the file is still downloading. */
    suspend fun streamUrl(torrentId: String, fileIndex: Int): String

    val player: PlayerFactory
}

/** Creates a media player bound to a URL. */
interface PlayerFactory {
    fun create(): MediaPlayerController
}

/** Minimal player contract used by the player screen. */
interface MediaPlayerController {
    val state: StateFlow<PlayerState>
    fun open(url: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun release()

    @androidx.compose.runtime.Composable
    fun Surface(modifier: androidx.compose.ui.Modifier)
}

data class PlayerState(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedFraction: Float = 0f,
    val buffering: Boolean = false,
    val volume: Float = 1f,
    val error: String? = null,
    val available: Boolean = true,
)

/** Remote control: the desktop serves, the phone connects. */
interface RemoteFeature {
    val isServer: Boolean

    /** Desktop: pairing QR payload; null when the server is disabled. */
    val pairingPayload: StateFlow<String?>
    val connectedClients: StateFlow<Int>
    suspend fun regenerateKey()

    /** Android: connect to a desktop from a scanned QR payload. Throws GraphiteException. */
    suspend fun pair(payload: String)
    suspend fun disconnect()
    suspend fun forget()
    val connected: StateFlow<Boolean>
    val remoteName: StateFlow<String?>
}

interface DesktopFeature {
    fun openMiniWindow()
}
