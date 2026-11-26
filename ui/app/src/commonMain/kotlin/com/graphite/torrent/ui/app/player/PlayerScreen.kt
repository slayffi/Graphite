package com.graphite.torrent.ui.app.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.ButtonKind
import com.graphite.torrent.ui.designsystem.components.GraphiteButton
import com.graphite.torrent.ui.designsystem.components.GraphiteIconButton
import com.graphite.torrent.ui.designsystem.format.Formatters
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Built-in player: black background, controls fade out after 3 seconds, the timeline shows how much
 * of the file is already downloaded so you know how far you can seek.
 */
@Composable
fun PlayerScreen(env: AppEnvironment, torrentId: String, fileIndex: Int, title: String, onBack: () -> Unit) {
    val streaming = env.features.streaming ?: return onBack()
    val player = remember { streaming.player.create() }
    val state by player.state.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf<String?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableFloatStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val motion = GraphiteTheme.motion

    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(torrentId, fileIndex) {
        try {
            env.controller.setSequential(torrentId, true)
            val u = streaming.streamUrl(torrentId, fileIndex)
            url = u
            player.open(u)
            player.play()
        } catch (e: Exception) {
            openError = e.message ?: "error"
        }
    }
    LaunchedEffect(torrentId, fileIndex) {
        while (true) {
            runCatching { env.controller.files(torrentId).firstOrNull { it.index == fileIndex }?.let { downloaded = it.progress } }
            delay(2000)
        }
    }
    LaunchedEffect(controlsVisible, interactions, state.playing) {
        if (controlsVisible && state.playing) {
            delay(motion.controlsAutoHideMs)
            controlsVisible = false
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun poke() {
        controlsVisible = true
        interactions++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Spacebar -> { if (state.playing) player.pause() else player.play(); poke(); true }
                    Key.DirectionRight -> { player.seekTo(state.positionMs + 10_000); poke(); true }
                    Key.DirectionLeft -> { player.seekTo((state.positionMs - 10_000).coerceAtLeast(0)); poke(); true }
                    Key.Escape -> { onBack(); true }
                    else -> false
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { if (controlsVisible) controlsVisible = false else poke() }) },
    ) {
        player.Surface(Modifier.fillMaxSize())

        if (!state.available || openError != null || state.error != null) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(if (!state.available) Res.string.player_unavailable else Res.string.player_error),
                    style = GraphiteTheme.typography.body,
                    color = GraphiteTheme.colors.textSecondary,
                )
                (openError ?: state.error)?.let { Text(it, style = GraphiteTheme.typography.numberCaption, color = GraphiteTheme.colors.textDisabled) }
                url?.let { u ->
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GraphiteButton(stringResource(Res.string.open_external), { env.platform.openUrl(u) }, icon = GraphiteIcons.ExternalLink)
                        GraphiteButton(stringResource(Res.string.copy_link), { env.platform.copyToClipboard(u) }, kind = ButtonKind.SECONDARY, icon = GraphiteIcons.Copy)
                    }
                }
            }
        }

        AnimatedVisibility(
            controlsVisible,
            enter = fadeIn(tween(motion.duration(motion.short))),
            exit = fadeOut(tween(motion.duration(motion.medium))),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .safeDrawingPadding()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GraphiteIconButton(GraphiteIcons.ArrowLeft, stringResource(Res.string.back), onBack)
                    Text(title, style = GraphiteTheme.typography.itemTitle, color = GraphiteTheme.colors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                }
                // Center controls
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    GraphiteIconButton(GraphiteIcons.Rewind, stringResource(Res.string.seek_back), { player.seekTo((state.positionMs - 10_000).coerceAtLeast(0)); poke() })
                    GraphiteIconButton(
                        if (state.playing) GraphiteIcons.Pause else GraphiteIcons.Play,
                        stringResource(if (state.playing) Res.string.pause else Res.string.play),
                        { if (state.playing) player.pause() else player.play(); poke() },
                        size = 72.dp,
                    )
                    GraphiteIconButton(GraphiteIcons.FastForward, stringResource(Res.string.seek_forward), { player.seekTo(state.positionMs + 10_000); poke() })
                }
                // Bottom: timeline
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .safeDrawingPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Timeline(
                        position = state.positionMs,
                        duration = state.durationMs,
                        downloaded = maxOf(downloaded, state.bufferedFraction),
                        onSeek = { player.seekTo(it); poke() },
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text(Formatters.clock(state.positionMs), style = GraphiteTheme.typography.numberCaption, color = GraphiteTheme.colors.textPrimary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(Res.string.downloaded_percent, Formatters.percent(downloaded, GraphiteTheme.units)),
                            style = GraphiteTheme.typography.numberCaption,
                            color = GraphiteTheme.colors.textSecondary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(Formatters.clock(state.durationMs), style = GraphiteTheme.typography.numberCaption, color = GraphiteTheme.colors.textPrimary)
                    }
                }
            }
        }
    }
}

/** Timeline: track, downloaded part (dim), played part (bright) and a thumb. Tap or drag to seek. */
@Composable
private fun Timeline(position: Long, duration: Long, downloaded: Float, onSeek: (Long) -> Unit) {
    val colors = GraphiteTheme.colors
    val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(played, 0f..1f) }
            .pointerInput(duration) {
                detectTapGestures { o -> if (duration > 0) onSeek((o.x / size.width * duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        dragFraction?.let { if (duration > 0) onSeek((it * duration).toLong()) }
                        dragFraction = null
                    },
                ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val h = 3.dp.toPx()
        val y = size.height / 2 - h / 2
        val r = CornerRadius(h / 2)
        drawRoundRect(colors.border, Offset(0f, y), Size(size.width, h), r)
        drawRoundRect(colors.textSecondary.copy(alpha = 0.6f), Offset(0f, y), Size(size.width * downloaded.coerceIn(0f, 1f), h), r)
        val shown = dragFraction ?: played
        drawRoundRect(colors.accentBright, Offset(0f, y), Size(size.width * shown, h), r)
        drawCircle(colors.accentBright, radius = 6.dp.toPx(), center = Offset(size.width * shown, size.height / 2))
    }
}

