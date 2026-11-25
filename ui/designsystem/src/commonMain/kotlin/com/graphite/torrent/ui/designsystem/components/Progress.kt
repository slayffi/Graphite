package com.graphite.torrent.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme

/** How a torrent looks; always paired with an icon and a text label (never colour alone). */
enum class VisualStatus { DOWNLOADING, METADATA, CHECKING, QUEUED, PAUSED, COMPLETED, ERROR, MOVING }

private val VisualStatus.isActive get() = this == VisualStatus.DOWNLOADING || this == VisualStatus.METADATA ||
    this == VisualStatus.CHECKING || this == VisualStatus.MOVING

/**
 * Thin pill progress bar: border-coloured track, accent → white gradient fill and a soft moving
 * highlight while active. Paused fades to the disabled grey; completion flashes white and dissolves.
 */
@Composable
fun GraphiteProgressBar(
    progress: Float,
    status: VisualStatus,
    modifier: Modifier = Modifier,
    height: Dp = GraphiteTheme.dimens.progressBar,
) {
    val colors = GraphiteTheme.colors
    val motion = GraphiteTheme.motion
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(motion.duration(motion.long), easing = motion.easing),
        label = "progress",
    )
    val fillColorStart by androidx.compose.animation.animateColorAsState(
        targetValue = when (status) {
            VisualStatus.PAUSED, VisualStatus.QUEUED -> colors.textDisabled
            VisualStatus.ERROR -> colors.error
            else -> colors.accent
        },
        animationSpec = tween(motion.duration(motion.long)),
        label = "fill-start",
    )
    val fillColorEnd by androidx.compose.animation.animateColorAsState(
        targetValue = when (status) {
            VisualStatus.PAUSED, VisualStatus.QUEUED -> colors.textDisabled
            VisualStatus.ERROR -> colors.error
            else -> colors.accentBright
        },
        animationSpec = tween(motion.duration(motion.long)),
        label = "fill-end",
    )

    // Completion: flash to white, then dissolve.
    val completion = remember { Animatable(if (status == VisualStatus.COMPLETED) 0f else 1f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(status) {
        if (status == VisualStatus.COMPLETED) {
            if (completion.value > 0f) {
                flash.snapTo(1f)
                flash.animateTo(0f, tween(motion.duration(motion.long * 2)))
                completion.animateTo(0f, tween(motion.duration(motion.long * 2)))
            }
        } else {
            completion.snapTo(1f)
            flash.snapTo(0f)
        }
    }

    val shimmerActive = status.isActive && !motion.reduceMotion
    val shimmer = if (shimmerActive) {
        val transition = rememberInfiniteTransition(label = "shimmer")
        transition.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(motion.shimmerPeriod, easing = LinearEasing), RepeatMode.Restart),
            label = "shimmer-x",
        ).value
    } else {
        null
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f) },
    ) {
        val alpha = completion.value
        if (alpha <= 0f) return@Canvas
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(color = colors.border.copy(alpha = colors.border.alpha * alpha), cornerRadius = radius)
        val indeterminate = status == VisualStatus.METADATA && animatedProgress <= 0f
        val fillWidth = if (indeterminate) size.width else size.width * animatedProgress
        if (fillWidth > 0f && !indeterminate) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(fillColorStart.copy(alpha = alpha), fillColorEnd.copy(alpha = alpha)),
                    startX = 0f,
                    endX = fillWidth.coerceAtLeast(1f),
                ),
                size = Size(fillWidth, size.height),
                cornerRadius = radius,
            )
        }
        if (shimmer != null && fillWidth > 0f) {
            val band = size.width * 0.25f
            val center = shimmer * size.width
            clipRect(right = fillWidth) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.45f * alpha), Color.Transparent),
                        startX = center - band / 2,
                        endX = center + band / 2,
                    ),
                    topLeft = Offset(center - band / 2, 0f),
                    size = Size(band, size.height),
                )
            }
        }
        if (flash.value > 0f) {
            drawRoundRect(color = colors.accentBright.copy(alpha = flash.value), cornerRadius = radius)
        }
    }
}

/** Circular progress used in the details header. */
@Composable
fun RingProgress(
    progress: Float,
    status: VisualStatus,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    stroke: Dp = 4.dp,
    content: @Composable () -> Unit = {},
) {
    val colors = GraphiteTheme.colors
    val motion = GraphiteTheme.motion
    val animated by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        tween(motion.duration(motion.long), easing = motion.easing),
        label = "ring",
    )
    val color = when (status) {
        VisualStatus.PAUSED, VisualStatus.QUEUED -> colors.textDisabled
        VisualStatus.ERROR -> colors.error
        VisualStatus.COMPLETED -> colors.accent
        else -> colors.accentBright
    }
    Box(
        modifier.size(size).semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(colors.border, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

@Preview
@Composable
private fun ProgressPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VisualStatus.entries.forEach { status ->
                Text(status.name, style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
                GraphiteProgressBar(0.62f, status)
            }
            RingProgress(0.62f, VisualStatus.DOWNLOADING) {
                Text("62%", style = GraphiteTheme.typography.numberBody)
            }
        }
    }
}
