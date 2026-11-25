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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme

enum class ButtonKind {
    /** Light accent fill with dark text. One per screen. */
    PRIMARY,

    /** Outlined. */
    SECONDARY,

    /** Text only. */
    TERTIARY,

    /** Outlined in the error colour; only used behind a confirmation. */
    DANGER,
}

@Composable
fun GraphiteButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = GraphiteTheme.colors
    val motion = GraphiteTheme.motion
    val shape = GraphiteTheme.shapes.control
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()

    val container = when (kind) {
        ButtonKind.PRIMARY -> when {
            !enabled -> colors.surface3
            pressed -> colors.accent.copy(alpha = 0.8f)
            hovered -> colors.accentBright
            else -> colors.accent
        }
        else -> when {
            !enabled -> Color.Transparent
            pressed -> colors.surface3
            hovered -> colors.surface2
            else -> Color.Transparent
        }
    }
    val content = when {
        !enabled -> colors.textDisabled
        kind == ButtonKind.PRIMARY -> colors.onAccent
        kind == ButtonKind.DANGER -> colors.errorText
        else -> colors.textPrimary
    }
    val borderColor = when {
        focused -> colors.accentBright
        kind == ButtonKind.SECONDARY -> if (enabled) colors.borderStrong else colors.border
        kind == ButtonKind.DANGER -> if (enabled) colors.error else colors.border
        else -> Color.Transparent
    }
    val animatedContainer by animateColorAsState(container, tween(motion.duration(motion.short)), label = "btn-bg")

    Row(
        modifier
            .defaultMinSize(minHeight = GraphiteTheme.dimens.minTouchTarget)
            .clip(shape)
            .background(animatedContainer, shape)
            .border(GraphiteTheme.dimens.borderWidth, borderColor, shape)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = GraphiteTheme.spacing.md, vertical = GraphiteTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(GraphiteTheme.spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(GraphiteTheme.dimens.icon))
        Text(text, style = GraphiteTheme.typography.label, color = content, maxLines = 1)
    }
}

/** Square icon button with a 48dp touch target. [contentDescription] is mandatory for screen readers. */
@Composable
fun GraphiteIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = GraphiteTheme.colors.textPrimary,
    size: Dp = GraphiteTheme.dimens.minTouchTarget,
    selected: Boolean = false,
) {
    val colors = GraphiteTheme.colors
    val shape = GraphiteTheme.shapes.control
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val bg by animateColorAsState(
        when {
            pressed || selected -> colors.surface3
            hovered -> colors.surface2
            else -> Color.Transparent
        },
        tween(GraphiteTheme.motion.duration(GraphiteTheme.motion.short)),
        label = "icon-btn",
    )
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(bg, shape)
            .then(if (focused) Modifier.border(1.dp, colors.accent, shape) else Modifier)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else colors.textDisabled, modifier = Modifier.size(GraphiteTheme.dimens.icon))
    }
}

/** Round floating "+" button, bottom-right. */
@Composable
fun GraphiteFab(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GraphiteTheme.colors
    val shape = GraphiteTheme.shapes.pill
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        when {
            pressed -> colors.accent.copy(alpha = 0.8f)
            hovered -> colors.accentBright
            else -> colors.accent
        },
        tween(GraphiteTheme.motion.duration(GraphiteTheme.motion.short)),
        label = "fab",
    )
    Box(
        modifier
            .size(GraphiteTheme.dimens.fab)
            .clip(shape)
            .background(bg, shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = colors.onAccent, modifier = Modifier.size(24.dp))
    }
}

/** Horizontal group of buttons, e.g. at the bottom of a sheet. */
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(GraphiteTheme.spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Preview
@Composable
private fun ButtonsPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GraphiteButton("Download", {}, icon = GraphiteIcons.Download)
            GraphiteButton("Cancel", {}, kind = ButtonKind.SECONDARY)
            GraphiteButton("More", {}, kind = ButtonKind.TERTIARY)
            GraphiteButton("Delete with files", {}, kind = ButtonKind.DANGER, icon = GraphiteIcons.Trash2)
            GraphiteButton("Disabled", {}, enabled = false)
            Row {
                GraphiteIconButton(GraphiteIcons.Pause, "Pause", {})
                GraphiteIconButton(GraphiteIcons.Settings, "Settings", {}, selected = true)
            }
            GraphiteFab(GraphiteIcons.Plus, "Add", {})
        }
    }
}
