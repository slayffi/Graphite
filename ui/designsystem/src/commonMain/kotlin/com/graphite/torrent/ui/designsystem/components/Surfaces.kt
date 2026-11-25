package com.graphite.torrent.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme

/**
 * Click / long-click with the Graphite feedback: hover and press lift the surface to surface-3,
 * keyboard focus draws a strong border. No ripple, no shadows.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.graphiteClickable(
    shape: Shape,
    enabled: Boolean = true,
    selected: Boolean = false,
    baseColor: Color? = null,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val colors = GraphiteTheme.colors
    val motion = GraphiteTheme.motion
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val base = baseColor ?: colors.surface1
    val target = when {
        !enabled -> base
        pressed || selected -> colors.surface3
        hovered -> colors.surface2
        else -> base
    }
    val background by animateColorAsState(target, tween(motion.duration(motion.short)), label = "surface")
    this
        .clip(shape)
        .background(background, shape)
        .then(if (focused) Modifier.border(1.dp, colors.accent, shape) else Modifier)
        .hoverable(interaction, enabled)
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/** Card: surface-1, 16dp radius, 1dp border, optional error outline. */
@Composable
fun GraphiteCard(
    modifier: Modifier = Modifier,
    error: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: Dp = GraphiteTheme.spacing.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GraphiteTheme.colors
    val shape = GraphiteTheme.shapes.card
    val borderColor = when {
        error -> colors.error
        selected -> colors.accent
        else -> colors.border
    }
    val clickable = if (onClick != null) {
        Modifier.graphiteClickable(shape, selected = selected, onLongClick = onLongClick, onClick = onClick)
    } else {
        Modifier.clip(shape).background(colors.surface1, shape)
    }
    Column(
        modifier
            .then(clickable)
            .border(GraphiteTheme.dimens.borderWidth, borderColor, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Raised panel for dialogs, sheets and menus (surface-2). */
@Composable
fun GraphitePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GraphiteTheme.colors
    val shape = GraphiteTheme.shapes.panel
    Column(
        modifier
            .clip(shape)
            .background(colors.surface2, shape)
            .border(GraphiteTheme.dimens.borderWidth, colors.borderStrong, shape)
            .padding(GraphiteTheme.spacing.lg),
        content = content,
    )
}

/** 1dp line in the border colour. */
@Composable
fun GraphiteDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(GraphiteTheme.dimens.borderWidth).background(GraphiteTheme.colors.border))
}

/** Small uppercase section header. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = GraphiteTheme.typography.overline,
        color = GraphiteTheme.colors.textSecondary,
        modifier = modifier.padding(vertical = GraphiteTheme.spacing.xs),
    )
}

@Preview
@Composable
private fun SurfacesPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Surfaces")
            GraphiteCard(onClick = {}) { Text("Card", style = GraphiteTheme.typography.body) }
            GraphiteCard(error = true) { Text("Error card", style = GraphiteTheme.typography.body) }
            GraphiteDivider()
            GraphitePanel { Text("Panel", style = GraphiteTheme.typography.body) }
        }
    }
}
