package com.graphite.torrent.android

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.settings.WatchFolderAction
import com.graphite.torrent.data.GraphiteServices
import com.graphite.torrent.data.automation.WatchFolderSource
import com.graphite.torrent.data.automation.WatchedFile
import com.graphite.torrent.data.remote.PairingCode
import com.graphite.torrent.data.remote.RemoteTorrentController
import com.graphite.torrent.ui.app.MediaPlayerController
import com.graphite.torrent.ui.app.PlayerFactory
import com.graphite.torrent.ui.app.PlayerState
import com.graphite.torrent.ui.app.RemoteFeature
import com.graphite.torrent.ui.app.StreamingFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Media3 ExoPlayer with custom (Compose) controls. */
class ExoPlayerController(context: Context) : MediaPlayerController {
    private val player = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(playing = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = error.errorCodeName) }
            }
        })
        scope.launch {
            while (isActive) {
                val duration = player.duration.takeIf { it > 0 } ?: 0L
                _state.update {
                    it.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = duration,
                        bufferedFraction = if (duration > 0) (player.bufferedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    )
                }
                delay(250)
            }
        }
    }

    override fun open(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun setVolume(volume: Float) {
        player.volume = volume
        _state.update { it.copy(volume = volume) }
    }

    override fun release() {
        scope.cancel()
        player.release()
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = this@ExoPlayerController.player
                }
            },
            modifier = modifier,
        )
    }
}

class AndroidStreaming(private val context: Context, private val services: GraphiteServices, private val storage: AndroidStorage) : StreamingFeature {
    override val player: PlayerFactory = object : PlayerFactory {
        override fun create(): MediaPlayerController = ExoPlayerController(context)
    }

    override suspend fun streamUrl(torrentId: String, fileIndex: Int): String = withContext(Dispatchers.IO) {
        val file = services.engine.files(torrentId).firstOrNull { it.index == fileIndex }
        if (file != null) return@withContext services.streams.url(torrentId, fileIndex, file.name)
        // Completed and moved to a SAF folder: play the document directly.
        val location = services.meta.get(torrentId)?.final_location
            ?: throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, torrentId)
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(location.substringBefore('#')))
            ?: throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, location)
        val root = location.substringAfter('#', "")
        val start = if (root.isEmpty()) tree else tree.findFile(root) ?: tree
        fun firstMedia(dir: DocumentFile): DocumentFile? {
            for (f in dir.listFiles()) {
                if (f.isDirectory) firstMedia(f)?.let { return it }
                else if (f.type?.startsWith("video") == true || f.type?.startsWith("audio") == true) return f
            }
            return null
        }
        (if (start.isFile) start else firstMedia(start))?.uri?.toString()
            ?: throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, location)
    }
}

/** The phone is the client side of remote control. */
class AndroidRemote(private val services: GraphiteServices, private val scope: CoroutineScope) : RemoteFeature {
    override val isServer = false
    override val pairingPayload: StateFlow<String?> = MutableStateFlow(null)
    override val connectedClients: StateFlow<Int> = MutableStateFlow(0)
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _name = MutableStateFlow<String?>(null)
    override val remoteName: StateFlow<String?> = _name.asStateFlow()
    private var client: RemoteTorrentController? = null
    private var watcher: kotlinx.coroutines.Job? = null

    override suspend fun regenerateKey() = Unit

    override suspend fun pair(payload: String) {
        val pairing = PairingCode.parse(payload) ?: throw GraphiteException(ErrorKind.REMOTE_AUTH, "not a Graphite pairing code")
        val remote = RemoteTorrentController(pairing, scope)
        if (!remote.ping()) throw GraphiteException(ErrorKind.REMOTE_UNREACHABLE, pairing.hosts.joinToString())
        services.settings.update { it.copy(remotePairing = pairing, remoteMode = true) }
        activate(remote)
    }

    fun activate(remote: RemoteTorrentController) {
        client?.stop()
        watcher?.cancel()
        client = remote
        remote.start()
        services.switchable.use(remote)
        _name.value = remote.name
        watcher = scope.launch { remote.connected.collect { _connected.value = it } }
    }

    /** Called at startup: reconnect when remote mode was on. */
    suspend fun restore() {
        val s = services.settings.current()
        val pairing = s.remotePairing ?: return
        _name.value = pairing.name
        if (s.remoteMode) activate(RemoteTorrentController(pairing, scope))
    }

    override suspend fun disconnect() {
        client?.stop()
        watcher?.cancel()
        client = null
        _connected.value = false
        services.switchable.useLocal()
        services.settings.update { it.copy(remoteMode = false) }
    }

    override suspend fun forget() {
        disconnect()
        services.settings.update { it.copy(remotePairing = null) }
        _name.value = null
    }
}

/** Watched folder chosen through the Storage Access Framework. */
class SafWatchFolderSource(private val context: Context) : WatchFolderSource {
    override fun supports(location: String) = location.startsWith("content://")

    override fun list(location: String): List<WatchedFile> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(location)) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile && (it.name?.lowercase()?.endsWith(".torrent") == true) }
            .sortedBy { it.lastModified() }
            .map { WatchedFile(it.name.orEmpty(), it.uri.toString()) }
    }

    override fun read(file: WatchedFile): ByteArray =
        context.contentResolver.openInputStream(Uri.parse(file.handle))?.use { it.readBytes() } ?: ByteArray(0)

    override fun finish(file: WatchedFile, action: WatchFolderAction, success: Boolean) {
        val doc = DocumentFile.fromSingleUri(context, Uri.parse(file.handle)) ?: return
        if (success && action == WatchFolderAction.DELETE) doc.delete()
        else if (!doc.renameTo(file.name + if (success) ".added" else ".invalid")) doc.delete()
    }
}
