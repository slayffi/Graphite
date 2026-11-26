package com.graphite.torrent.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.graphite.torrent.ui.app.MediaPlayerController
import com.graphite.torrent.ui.app.PlayerFactory
import com.graphite.torrent.ui.app.PlayerState
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
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
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.io.File

/**
 * Minimal binding to libVLC 3 (LGPL) through JNA. Frames are rendered by libVLC into memory (RV32)
 * and drawn by Compose, so the player controls can be overlaid on the video.
 */
@Suppress("FunctionName")
internal interface LibVlc : Library {
    fun libvlc_new(argc: Int, argv: Array<String>): Pointer?
    fun libvlc_release(instance: Pointer)
    fun libvlc_media_new_location(instance: Pointer, mrl: String): Pointer?
    fun libvlc_media_release(media: Pointer)
    fun libvlc_media_player_new_from_media(media: Pointer): Pointer?
    fun libvlc_media_player_release(player: Pointer)
    fun libvlc_media_player_play(player: Pointer): Int
    fun libvlc_media_player_set_pause(player: Pointer, pause: Int)
    fun libvlc_media_player_stop(player: Pointer)
    fun libvlc_media_player_is_playing(player: Pointer): Int
    fun libvlc_media_player_get_time(player: Pointer): Long
    fun libvlc_media_player_set_time(player: Pointer, time: Long)
    fun libvlc_media_player_get_length(player: Pointer): Long
    fun libvlc_media_player_get_state(player: Pointer): Int
    fun libvlc_audio_set_volume(player: Pointer, volume: Int): Int
    fun libvlc_video_get_size(player: Pointer, num: Int, px: IntByReference, py: IntByReference): Int
    fun libvlc_video_set_callbacks(player: Pointer, lock: LockCallback, unlock: UnlockCallback?, display: DisplayCallback?, opaque: Pointer?)
    fun libvlc_video_set_format_callbacks(player: Pointer, setup: FormatCallback, cleanup: CleanupCallback?)

    interface LockCallback : Callback { fun lock(opaque: Pointer?, planes: PointerByReference): Pointer? }
    interface UnlockCallback : Callback { fun unlock(opaque: Pointer?, picture: Pointer?, planes: Pointer?) }
    interface DisplayCallback : Callback { fun display(opaque: Pointer?, picture: Pointer?) }
    interface FormatCallback : Callback {
        fun setup(opaque: PointerByReference, chroma: Pointer, width: IntByReference, height: IntByReference, pitches: Pointer, lines: Pointer): Int
    }
    interface CleanupCallback : Callback { fun cleanup(opaque: Pointer?) }
}

internal object LibVlcLoader {
    /** Bundled VLC (MSI) first, then a system installation. */
    val api: LibVlc? by lazy {
        val candidates = listOfNotNull(
            DesktopPaths.appResources?.let { File(it, "vlc") },
            File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "VideoLAN\\VLC"),
            File(System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "VideoLAN\\VLC"),
            File("/usr/lib/x86_64-linux-gnu"),
            File("/usr/lib64"),
            File("/usr/lib"),
            File("/Applications/VLC.app/Contents/MacOS/lib"),
        ).filter { it.isDirectory }
        candidates.forEach {
            NativeLibrary.addSearchPath("libvlc", it.absolutePath)
            NativeLibrary.addSearchPath("vlc", it.absolutePath)
            NativeLibrary.addSearchPath("libvlccore", it.absolutePath)
        }
        runCatching { Native.load(if (DesktopPaths.isWindows) "libvlc" else "vlc", LibVlc::class.java) }.getOrNull()
    }
}

class VlcPlayerFactory : PlayerFactory {
    override fun create(): MediaPlayerController = VlcPlayer()
}

class VlcPlayer : MediaPlayerController {
    private val api = LibVlcLoader.api
    private val instance: Pointer? = api?.libvlc_new(ARGS.size, ARGS)
    private var media: Pointer? = null
    @Volatile private var player: Pointer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(PlayerState(available = api != null && instance != null))
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val frame = mutableStateOf<ImageBitmap?>(null)
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var buffer: Memory? = null
    private val bitmaps = arrayOf(Bitmap(), Bitmap())
    private var pixels = ByteArray(0)
    private var next = 0

    /** Number of frames rendered so far and the decoded size (diagnostics and tests). */
    internal val renderedFrames = java.util.concurrent.atomic.AtomicInteger()
    internal val videoSize: Pair<Int, Int> get() = width to height

    // Strong references: libVLC keeps raw pointers to these callbacks.
    private val formatCallback = object : LibVlc.FormatCallback {
        override fun setup(opaque: PointerByReference, chroma: Pointer, width: IntByReference, height: IntByReference, pitches: Pointer, lines: Pointer): Int {
            var w = width.value
            var h = height.value
            // The decoder's buffer can be taller than the picture (line alignment); ask for the visible size
            // so VLC crops the padding instead of showing it as a bar.
            val p = player
            if (p != null) {
                val vw = IntByReference()
                val vh = IntByReference()
                if (api?.libvlc_video_get_size(p, 0, vw, vh) == 0 && vw.value > 0 && vh.value > 0) {
                    w = vw.value
                    h = vh.value
                }
            }
            if (w <= 0 || h <= 0) return 0
            if (w > MAX_WIDTH) {
                h = (h.toLong() * MAX_WIDTH / w).toInt()
                w = MAX_WIDTH
            }
            chroma.write(0, "RV32".toByteArray(), 0, 4)
            width.value = w
            height.value = h
            pitches.setInt(0, w * 4)
            lines.setInt(0, h)
            this@VlcPlayer.width = w
            this@VlcPlayer.height = h
            buffer = Memory(w.toLong() * h * 4)
            pixels = ByteArray(w * h * 4)
            return 1
        }
    }
    private val cleanupCallback = object : LibVlc.CleanupCallback {
        override fun cleanup(opaque: Pointer?) {
            buffer = null
        }
    }
    private val lockCallback = object : LibVlc.LockCallback {
        override fun lock(opaque: Pointer?, planes: PointerByReference): Pointer? {
            planes.value = buffer
            return null
        }
    }
    private val unlockCallback = object : LibVlc.UnlockCallback {
        override fun unlock(opaque: Pointer?, picture: Pointer?, planes: Pointer?) = Unit
    }
    private val displayCallback = object : LibVlc.DisplayCallback {
        override fun display(opaque: Pointer?, picture: Pointer?) {
            val mem = buffer ?: return
            val w = width
            val h = height
            if (w <= 0 || h <= 0 || pixels.size != w * h * 4) return
            mem.read(0, pixels, 0, pixels.size)
            val bitmap = bitmaps[next]
            next = 1 - next
            bitmap.installPixels(ImageInfo.makeN32(w, h, ColorAlphaType.OPAQUE), pixels, w * 4)
            frame.value = bitmap.asComposeImageBitmap()
            renderedFrames.incrementAndGet()
        }
    }

    override fun open(url: String) {
        val vlc = api ?: return
        val inst = instance ?: return
        releaseMedia()
        val m = vlc.libvlc_media_new_location(inst, url) ?: return
        val p = vlc.libvlc_media_player_new_from_media(m) ?: return
        media = m
        player = p
        vlc.libvlc_video_set_format_callbacks(p, formatCallback, cleanupCallback)
        vlc.libvlc_video_set_callbacks(p, lockCallback, unlockCallback, displayCallback, null)
        scope.launch { poll() }
    }

    private suspend fun poll() {
        val vlc = api ?: return
        while (scope.isActive) {
            val p = player ?: break
            val stateCode = vlc.libvlc_media_player_get_state(p)
            _state.update {
                it.copy(
                    playing = vlc.libvlc_media_player_is_playing(p) == 1,
                    positionMs = vlc.libvlc_media_player_get_time(p).coerceAtLeast(0),
                    durationMs = vlc.libvlc_media_player_get_length(p).coerceAtLeast(0),
                    buffering = stateCode == STATE_BUFFERING || stateCode == STATE_OPENING,
                    error = if (stateCode == STATE_ERROR) "libVLC" else null,
                )
            }
            delay(250)
        }
    }

    override fun play() {
        player?.let { api?.libvlc_media_player_play(it) }
    }

    override fun pause() {
        player?.let { api?.libvlc_media_player_set_pause(it, 1) }
    }

    override fun seekTo(positionMs: Long) {
        player?.let { api?.libvlc_media_player_set_time(it, positionMs.coerceAtLeast(0)) }
    }

    override fun setVolume(volume: Float) {
        player?.let { api?.libvlc_audio_set_volume(it, (volume * 100).toInt().coerceIn(0, 200)) }
        _state.update { it.copy(volume = volume) }
    }

    private fun releaseMedia() {
        val vlc = api ?: return
        player?.let {
            vlc.libvlc_media_player_stop(it)
            vlc.libvlc_media_player_release(it)
        }
        media?.let { vlc.libvlc_media_release(it) }
        player = null
        media = null
    }

    override fun release() {
        scope.cancel()
        releaseMedia()
        instance?.let { api?.libvlc_release(it) }
        bitmaps.forEach { it.close() }
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        val image = frame.value
        Canvas(modifier) {
            val img = image ?: return@Canvas
            val scale = minOf(size.width / img.width, size.height / img.height)
            val w = (img.width * scale).toInt()
            val h = (img.height * scale).toInt()
            drawImage(
                img,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(img.width, img.height),
                dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                dstSize = IntSize(w, h),
            )
        }
    }

    private companion object {
        val ARGS = arrayOf("--no-video-title-show", "--quiet", "--no-snapshot-preview", "--network-caching=3000")
        const val MAX_WIDTH = 1920
        const val STATE_OPENING = 1
        const val STATE_BUFFERING = 2
        const val STATE_ERROR = 7
    }
}
