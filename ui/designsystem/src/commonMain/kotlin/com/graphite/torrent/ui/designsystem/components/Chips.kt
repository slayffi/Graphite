package com.graphite.torrent.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme

/** Pill-shaped filter chip with an optional counter. Selected = light fill. */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    icon: ImageVector? = null,
) {
    val colors = GraphiteTheme.colors
    val shape = GraphiteTheme.shapes.pill
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val motion = GraphiteTheme.motion
    val bg by animateColorAsState(
        when {
            selected -> colors.accent
            hovered -> colors.surface2
            else -> Color.Transparent
        },
        tween(motion.duration(motion.short)),
        label = "chip-bg",
    )
    val fg = if (selected) colors.onAccent else colors.textPrimary
    val secondary = if (selected) colors.onAccent.copy(alpha = 0.7f) else colors.textSecondary
    Row(
        modifier
            .defaultMinSize(minHeight = GraphiteTheme.dimens.minTouchTarget)
            .semantics { this.selected = selected }
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, if (focused) colors.accentBright else if (selected) colors.accent else colors.borderStrong, shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = GraphiteTheme.spacing.sm, vertical = GraphiteTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(GraphiteTheme.dimens.iconSmall))
        Text(label, style = GraphiteTheme.typography.label, color = fg, maxLines = 1)
        if (count != null) Text(count.toString(), style = GraphiteTheme.typography.numberCaption, color = secondary)
    }
}

/** Small read-only tag (category or tag on a card). */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = GraphiteTheme.colors
    val shape = GraphiteTheme.shapes.pill
    Row(
        modifier
            .heightIn(min = 22.dp)
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = GraphiteTheme.spacing.xs, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(12.dp))
        Text(text, style = GraphiteTheme.typography.caption, color = colors.textSecondary, maxLines = 1)
    }
}

/** Segmented control for 2–4 exclusive options (speed profiles, chart periods). */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
) {
    val colors = GraphiteTheme.colors
    val shape = GraphiteTheme.shapes.control
    Row(
        modifier
            .clip(shape)
            .background(colors.surface1, shape)
            .border(1.dp, colors.border, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val segmentShape = GraphiteTheme.shapes.control
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val bg by animateColorAsState(
                when {
                    isSelected -> colors.surface3
                    hovered -> colors.surface2
                    else -> Color.Transparent
                },
                tween(GraphiteTheme.motion.duration(GraphiteTheme.motion.short)),
                label = "segment",
            )
            Row(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .semantics { this.selected = isSelected }
                    .clip(segmentShape)
                    .background(bg, segmentShape)
                    .hoverable(interaction)
                    .clickable(interaction, indication = null, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = GraphiteTheme.spacing.xs, vertical = GraphiteTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ic = icon?.invoke(option)
                val fg = if (isSelected) colors.textPrimary else colors.textSecondary
                if (ic != null) Icon(ic, null, tint = fg, modifier = Modifier.size(GraphiteTheme.dimens.iconSmall))
                Text(label(option), style = GraphiteTheme.typography.label, color = fg, maxLines = 1)
            }
        }
    }
}

@Preview
@Composable
private fun ChipsPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("All", true, {}, count = 12)
                FilterChip("Active", false, {}, count = 3)
                FilterChip("Paused", false, {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagChip("Movies", icon = GraphiteIcons.Layers)
                TagChip("4k", icon = GraphiteIcons.Tag)
            }
            SegmentedControl(listOf("Turbo", "Balance", "Saver"), "Turbo", { it }, {})
        }
    }
}
