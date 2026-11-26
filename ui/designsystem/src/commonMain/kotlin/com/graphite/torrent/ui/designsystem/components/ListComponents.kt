package com.graphite.torrent.ui.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme

/**
 * Top of the downloads screen: total speed in large numbers, a one-minute mini chart and counters.
 * Speed and progress are the most important things on the screen, everything else is secondary.
 */
@Composable
fun SummaryHeader(
    speedText: String,
    speedLabel: String,
    history: List<Long>,
    activeText: String,
    uploadText: String,
    modifier: Modifier = Modifier,
    chartDescription: String = "",
    valueLabel: (Long) -> String = { it.toString() },
) {
    val colors = GraphiteTheme.colors
    GraphiteCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(speedLabel, style = GraphiteTheme.typography.caption, color = colors.textSecondary)
                RollingText(speedText, GraphiteTheme.typography.displayNumber)
                Spacer(Modifier.size(GraphiteTheme.spacing.xxs))
                Text(activeText, style = GraphiteTheme.typography.caption, color = colors.textSecondary)
                Text(uploadText, style = GraphiteTheme.typography.numberCaption, color = colors.textSecondary)
            }
            Spacer(Modifier.width(GraphiteTheme.spacing.md))
            SpeedChart(
                values = history,
                modifier = Modifier.weight(1f),
                height = 72.dp,
                contentDescription = chartDescription,
                valueLabel = { _, v -> valueLabel(v) },
            )
        }
    }
}

/** Row of the file tree: indentation, expander, tri-state checkbox, name, size and a trailing slot. */
@Composable
fun TreeRow(
    title: String,
    subtitle: String,
    depth: Int,
    icon: ImageVector,
    checkState: ToggleableState,
    onCheckedClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    onExpandClick: (() -> Unit)? = null,
    progress: Float? = null,
    expandLabel: String = "",
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = GraphiteTheme.colors
    val rotation by animateFloatAsState(if (expanded == true) 90f else 0f, tween(GraphiteTheme.motion.duration(GraphiteTheme.motion.short)), label = "chevron")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = GraphiteTheme.dimens.minTouchTarget)
            .padding(start = (depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded != null && onExpandClick != null) {
            GraphiteIconButton(
                GraphiteIcons.ChevronRight,
                expandLabel,
                onExpandClick,
                size = 36.dp,
                tint = colors.textSecondary,
                modifier = Modifier.rotate(rotation),
            )
        } else {
            Spacer(Modifier.width(36.dp))
        }
        GraphiteCheckbox(checkState, onCheckedClick)
        Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(GraphiteTheme.dimens.iconSmall))
        Spacer(Modifier.width(GraphiteTheme.spacing.xs))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$title, $subtitle" }) {
            MiddleEllipsisText(title, GraphiteTheme.typography.body)
            Text(subtitle, style = GraphiteTheme.typography.numberCaption, color = colors.textSecondary, maxLines = 1)
            if (progress != null) {
                Spacer(Modifier.size(4.dp))
                // Dozens of these are on screen at once: no moving highlight.
                GraphiteProgressBar(progress, if (progress >= 1f) VisualStatus.DOWNLOADING else VisualStatus.PAUSED, height = 2.dp, animated = false)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(GraphiteTheme.spacing.xs))
            trailing()
        }
    }
}

/** Two-line row for peers and trackers with a monospaced value on the right. */
@Composable
fun DetailRow(
    title: String,
    subtitle: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: androidx.compose.ui.graphics.Color = GraphiteTheme.colors.textSecondary,
    secondaryValue: String? = null,
) {
    val colors = GraphiteTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = GraphiteTheme.dimens.minTouchTarget).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraphiteTheme.spacing.sm),
    ) {
        if (icon != null) Icon(icon, null, tint = iconTint, modifier = Modifier.size(GraphiteTheme.dimens.iconSmall))
        Column(Modifier.weight(1f)) {
            Text(title, style = GraphiteTheme.typography.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = GraphiteTheme.typography.caption, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = GraphiteTheme.typography.numberCaption, color = colors.textPrimary, maxLines = 1)
            if (secondaryValue != null) Text(secondaryValue, style = GraphiteTheme.typography.numberCaption, color = colors.textSecondary, maxLines = 1)
        }
    }
}

@Preview
@Composable
private fun ListComponentsPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryHeader("24,8 МБ/с", "Общая скорость", List(60) { (it % 13) * 1_000_000L }, "Активных: 3", "Отдача: 0 Б ✓")
            TreeRow("Season 2", "4 файла · 12,3 ГБ", 0, GraphiteIcons.Folder, ToggleableState.Indeterminate, {}, expanded = true, onExpandClick = {})
            TreeRow("S02E07.mkv", "2,8 ГБ", 1, GraphiteIcons.FileVideo, ToggleableState.On, {}, progress = 0.4f) {
                TagChip("Высокий")
            }
            DetailRow("192.168.1.20:51413", "qBittorrent 5.1 · DHT · µTP", "1,2 МБ/с", icon = GraphiteIcons.Users, secondaryValue = "48%")
        }
    }
}
