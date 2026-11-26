package com.graphite.torrent.ui.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme

/** Everything a torrent card displays, already formatted and localized by the caller. */
@Immutable
data class TorrentCardState(
    val id: String,
    val name: String,
    val status: VisualStatus,
    val statusLabel: String,
    val progress: Float,
    val percentText: String,
    val speedText: String,
    val sizeText: String,
    val etaText: String,
    val errorText: String? = null,
    val tags: List<String> = emptyList(),
)

fun VisualStatus.icon(): ImageVector = when (this) {
    VisualStatus.DOWNLOADING -> GraphiteIcons.ArrowDown
    VisualStatus.METADATA -> GraphiteIcons.Magnet
    VisualStatus.CHECKING -> GraphiteIcons.RefreshCw
    VisualStatus.QUEUED -> GraphiteIcons.Hourglass
    VisualStatus.PAUSED -> GraphiteIcons.Pause
    VisualStatus.COMPLETED -> GraphiteIcons.Check
    VisualStatus.ERROR -> GraphiteIcons.TriangleAlert
    VisualStatus.MOVING -> GraphiteIcons.FolderInput
}

/** Status icon (spinning while checking) in the matching tone. */
@Composable
fun StatusIcon(status: VisualStatus, modifier: Modifier = Modifier) {
    val colors = GraphiteTheme.colors
    val tint = when (status) {
        VisualStatus.ERROR -> colors.error
        VisualStatus.PAUSED, VisualStatus.QUEUED -> colors.textSecondary
        VisualStatus.DOWNLOADING, VisualStatus.METADATA -> colors.accentBright
        else -> colors.accent
    }
    val spinning = status == VisualStatus.CHECKING && !GraphiteTheme.motion.reduceMotion
    val base = modifier.size(GraphiteTheme.dimens.iconSmall)
    val spin = if (spinning) {
        val angle = rememberInfiniteTransition(label = "spin").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "spin-angle",
        )
        // Layer property: the icon turns without being recomposed on every frame.
        base.graphicsLayer { rotationZ = angle.value }
    } else {
        base
    }
    Icon(status.icon(), contentDescription = null, tint = tint, modifier = spin)
}

/**
 * Torrent card: name (middle-ellipsized), "status · speed · done of total" below, big monospaced
 * percent with the time left on the right and a 3dp progress bar along the bottom edge.
 */
@Composable
fun TorrentCard(
    state: TorrentCardState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = GraphiteTheme.colors
    val spacing = GraphiteTheme.spacing
    val shape = GraphiteTheme.shapes.card
    val isError = state.status == VisualStatus.ERROR
    val borderColor = when {
        isError -> colors.error
        selected -> colors.accent
        else -> colors.border
    }
    val details = buildList {
        // While downloading the arrow and the speed already say it; the word would push the size off a phone screen.
        val speedSaysIt = state.status == VisualStatus.DOWNLOADING && state.speedText.isNotEmpty()
        if (!speedSaysIt) add(state.statusLabel)
        if (state.speedText.isNotEmpty()) add(state.speedText)
        if (state.sizeText.isNotEmpty()) add(state.sizeText)
    }.joinToString(" · ")

    Box(
        modifier
            .fillMaxWidth()
            .graphiteClickable(shape, selected = selected, onLongClick = onLongClick, onClick = onClick)
            .border(GraphiteTheme.dimens.borderWidth, borderColor, shape)
            .semantics(mergeDescendants = true) {
                contentDescription = "${state.name}. ${state.statusLabel}. ${state.percentText}"
                stateDescription = listOf(state.speedText, state.sizeText, state.etaText).filter { it.isNotEmpty() }.joinToString(" · ")
            },
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = spacing.md, end = spacing.md, top = spacing.md, bottom = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(selectionMode, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    SelectionMark(selected, Modifier.padding(end = spacing.sm))
                }
                Column(Modifier.weight(1f)) {
                    MiddleEllipsisText(state.name, GraphiteTheme.typography.itemTitle)
                    Spacer(Modifier.height(spacing.xxs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(state.status)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            details,
                            style = GraphiteTheme.typography.numberCaption,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.errorText != null) {
                        Spacer(Modifier.height(spacing.xxs))
                        Text(
                            state.errorText,
                            style = GraphiteTheme.typography.caption,
                            color = colors.errorText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.tags.isNotEmpty()) {
                        Spacer(Modifier.height(spacing.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                            state.tags.take(3).forEach { TagChip(it) }
                        }
                    }
                }
                Spacer(Modifier.width(spacing.sm))
                // Percent with the time left under it: the line on the left stays short enough for a phone.
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        state.percentText,
                        style = GraphiteTheme.typography.numberLarge,
                        color = if (state.status == VisualStatus.PAUSED || state.status == VisualStatus.QUEUED) colors.textSecondary else colors.textPrimary,
                        maxLines = 1,
                    )
                    if (state.etaText.isNotEmpty()) {
                        Text(state.etaText, style = GraphiteTheme.typography.numberCaption, color = colors.textSecondary, maxLines = 1)
                    }
                }
            }
            GraphiteProgressBar(state.progress, state.status, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = GraphiteTheme.colors
    val shape = GraphiteTheme.shapes.pill
    Box(
        modifier
            .size(22.dp)
            .clip(shape)
            .background(if (selected) colors.accent else Color.Transparent, shape)
            .border(1.dp, if (selected) colors.accent else colors.borderStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(GraphiteIcons.Check, null, tint = colors.onAccent, modifier = Modifier.size(14.dp))
    }
}

/** Shimmering placeholder used while data loads. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    val colors = GraphiteTheme.colors
    val motion = GraphiteTheme.motion
    val x: State<Float>? = if (motion.reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            -1f, 2f, infiniteRepeatable(tween(motion.shimmerPeriod, easing = LinearEasing)), label = "skeleton-x",
        )
    }
    val highlight = colors.surface3
    Box(
        modifier
            .clip(GraphiteTheme.shapes.control)
            .background(colors.surface2)
            .drawBehind {
                val at = x?.value ?: 0.5f
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, highlight, Color.Transparent),
                        start = androidx.compose.ui.geometry.Offset(at * 600f - 300f, 0f),
                        end = androidx.compose.ui.geometry.Offset(at * 600f, 0f),
                    ),
                )
            },
    )
}

@Composable
fun SkeletonTorrentCard(modifier: Modifier = Modifier) {
    val spacing = GraphiteTheme.spacing
    GraphiteCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(16.dp))
                Spacer(Modifier.height(spacing.xs))
                SkeletonBlock(Modifier.fillMaxWidth(0.45f).height(12.dp))
            }
            Spacer(Modifier.width(spacing.md))
            SkeletonBlock(Modifier.size(width = 44.dp, height = 20.dp))
        }
    }
}

@Preview
@Composable
private fun TorrentCardPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TorrentCard(
                TorrentCardState(
                    id = "1",
                    name = "Some.Series.S02E07.Episode.Title.2160p.WEB-DL.DDP5.1.HDR.mkv",
                    status = VisualStatus.DOWNLOADING,
                    statusLabel = "Загрузка",
                    progress = 0.62f,
                    percentText = "62%",
                    speedText = "12,4 МБ/с",
                    sizeText = "2,8 из 4,5 ГБ",
                    etaText = "2м 18с",
                    tags = listOf("Сериалы"),
                ),
                onClick = {},
            )
            TorrentCard(
                TorrentCardState("2", "Ubuntu 26.04 Desktop amd64.iso", VisualStatus.PAUSED, "Пауза", 0.31f, "31%", "", "1,9 ГБ из 6,1 ГБ", ""),
                onClick = {},
            )
            TorrentCard(
                TorrentCardState("3", "Album — FLAC", VisualStatus.ERROR, "Ошибка", 0.1f, "10%", "", "64 МБ из 640 МБ", "", errorText = "Нет места на диске. Освободите место или выберите другую папку."),
                onClick = {},
            )
            TorrentCard(
                TorrentCardState("4", "Documentary.1080p.mkv", VisualStatus.COMPLETED, "Готово", 1f, "100%", "", "3,2 ГБ", ""),
                onClick = {},
                selected = true,
                selectionMode = true,
            )
            SkeletonTorrentCard()
        }
    }
}
