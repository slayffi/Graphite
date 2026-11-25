package com.graphite.torrent.ui.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.platform.isDesktop
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme

/**
 * Sheet that adapts to the platform: a bottom sheet on Android, a centred modal panel on desktop.
 * Escape / back / tapping outside dismiss it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphiteSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GraphiteTheme.colors
    val spacing = GraphiteTheme.spacing
    if (isDesktop) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier
                        .widthIn(min = 360.dp, max = 560.dp)
                        .heightIn(max = 720.dp)
                        .padding(spacing.lg)
                        .clip(GraphiteTheme.shapes.panel)
                        .background(colors.surface2)
                        .border(1.dp, colors.borderStrong, GraphiteTheme.shapes.panel)
                        .padding(spacing.lg),
                ) {
                    if (title != null) SheetTitle(title, onDismiss)
                    Column(Modifier.verticalScroll(rememberScrollState()), content = content)
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surface2,
            contentColor = colors.textPrimary,
            scrimColor = colors.scrim,
            shape = GraphiteTheme.shapes.sheet,
            tonalElevation = 0.dp,
            dragHandle = {
                Box(
                    Modifier
                        .padding(top = spacing.sm, bottom = spacing.xs)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(GraphiteTheme.shapes.pill)
                        .background(colors.borderStrong),
                )
            },
        ) {
            Column(
                modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.md)
                    .padding(bottom = spacing.lg)
                    .navigationBarsPadding(),
            ) {
                if (title != null) SheetTitle(title, onDismiss)
                content()
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = GraphiteTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = GraphiteTheme.typography.itemTitle,
            color = GraphiteTheme.colors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (isDesktop) GraphiteIconButton(GraphiteIcons.X, "Close", onDismiss, size = 40.dp, tint = GraphiteTheme.colors.textSecondary)
    }
}

/** Confirmation for dangerous actions. The danger button is never the default. */
@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    GraphiteSheet(onDismiss = onDismiss, title = title) {
        Text(message, style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary)
        if (extra != null) {
            Spacer(Modifier.height(GraphiteTheme.spacing.sm))
            extra()
        }
        Spacer(Modifier.height(GraphiteTheme.spacing.lg))
        ButtonRow(Modifier.fillMaxWidth()) {
            GraphiteButton(cancelLabel, onDismiss, kind = ButtonKind.SECONDARY)
            GraphiteButton(confirmLabel, onConfirm, kind = if (danger) ButtonKind.DANGER else ButtonKind.PRIMARY)
        }
    }
}

/** Tabs with a thin underline indicator; scrolls horizontally on narrow screens. */
@Composable
fun GraphiteTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GraphiteTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(GraphiteTheme.spacing.xxs)) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val fg by animateColorAsState(
                    if (selected) colors.textPrimary else if (hovered) colors.textPrimary.copy(alpha = 0.85f) else colors.textSecondary,
                    tween(GraphiteTheme.motion.duration(GraphiteTheme.motion.short)),
                    label = "tab",
                )
                Column(
                    Modifier
                        .semantics { this.selected = selected }
                        .clip(GraphiteTheme.shapes.control)
                        .hoverable(interaction)
                        .clickable(interaction, indication = null, role = Role.Tab) { onSelect(index) }
                        .heightIn(min = GraphiteTheme.dimens.minTouchTarget)
                        .padding(horizontal = GraphiteTheme.spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(label, style = GraphiteTheme.typography.label, color = fg, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(24.dp)
                            .clip(GraphiteTheme.shapes.pill)
                            .background(if (selected) colors.accentBright else Color.Transparent),
                    )
                }
            }
        }
        GraphiteDivider()
    }
}

@Immutable
data class NavItem(val key: String, val icon: ImageVector, val label: String)

/** Android bottom navigation (three destinations). */
@Composable
fun GraphiteBottomBar(items: List<NavItem>, selectedKey: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = GraphiteTheme.colors
    Column(modifier.fillMaxWidth().background(colors.bg)) {
        GraphiteDivider()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { item ->
                val selected = item.key == selectedKey
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { this.selected = selected }
                        .clickable(role = Role.Tab, onClickLabel = item.label) { onSelect(item.key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .clip(GraphiteTheme.shapes.pill)
                            .background(if (selected) colors.surface3 else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Icon(item.icon, null, tint = if (selected) colors.textPrimary else colors.textSecondary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(item.label, style = GraphiteTheme.typography.caption, color = if (selected) colors.textPrimary else colors.textSecondary)
                }
            }
        }
    }
}

/** Desktop sidebar: 64dp of icons that expands to 220dp with labels. */
@Composable
fun GraphiteSidebar(
    items: List<NavItem>,
    selectedKey: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelect: (String) -> Unit,
    expandLabel: String,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = GraphiteTheme.colors
    val dimens = GraphiteTheme.dimens
    val width by animateDpAsState(
        if (expanded) dimens.sidebarExpanded else dimens.sidebarCollapsed,
        tween(GraphiteTheme.motion.duration(GraphiteTheme.motion.medium), easing = GraphiteTheme.motion.easing),
        label = "sidebar",
    )
    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier.width(width).fillMaxHeight().background(colors.bg).padding(vertical = GraphiteTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(GraphiteTheme.spacing.xxs),
        ) {
            SidebarEntry(GraphiteIcons.ListTree, expandLabel, selected = false, expanded = expanded, onClick = onToggleExpanded)
            Spacer(Modifier.height(GraphiteTheme.spacing.xs))
            items.forEach { item ->
                SidebarEntry(item.icon, item.label, item.key == selectedKey, expanded) { onSelect(item.key) }
            }
            Spacer(Modifier.weight(1f))
            footer?.invoke(this)
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))
    }
}

@Composable
private fun SidebarEntry(icon: ImageVector, label: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val colors = GraphiteTheme.colors
    Row(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .height(GraphiteTheme.dimens.minTouchTarget)
            .semantics { this.selected = selected }
            .graphiteClickable(GraphiteTheme.shapes.control, selected = selected, baseColor = colors.bg, role = Role.Tab, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = if (expanded) null else label, tint = if (selected) colors.textPrimary else colors.textSecondary, modifier = Modifier.size(GraphiteTheme.dimens.icon))
        AnimatedVisibility(expanded, enter = fadeIn(tween(GraphiteTheme.motion.duration(150))), exit = fadeOut(tween(GraphiteTheme.motion.duration(100)))) {
            Text(
                label,
                style = GraphiteTheme.typography.label,
                color = if (selected) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(start = GraphiteTheme.spacing.sm),
            )
        }
    }
}

/** Snackbars on surface-3 with primary text. */
@Composable
fun GraphiteSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = GraphiteTheme.colors
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = GraphiteTheme.shapes.control,
            containerColor = colors.surface3,
            contentColor = colors.textPrimary,
            actionColor = colors.accentBright,
            actionContentColor = colors.accentBright,
            dismissActionContentColor = colors.textSecondary,
        )
    }
}

@Immutable
data class MenuAction(val label: String, val icon: ImageVector? = null, val danger: Boolean = false, val onClick: () -> Unit)

/** Dropdown / context menu on surface-2 with a border. */
@Composable
fun GraphiteMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>, offset: DpOffset = DpOffset.Zero) {
    val colors = GraphiteTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = GraphiteTheme.shapes.control,
        containerColor = colors.surface2,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, colors.borderStrong),
    ) {
        actions.forEach { action ->
            val tint = if (action.danger) colors.errorText else colors.textPrimary
            DropdownMenuItem(
                text = { Text(action.label, style = GraphiteTheme.typography.body, color = tint) },
                leadingIcon = action.icon?.let { { Icon(it, null, tint = tint, modifier = Modifier.size(GraphiteTheme.dimens.icon)) } },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
            )
        }
    }
}

@Preview
@Composable
private fun ContainersPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GraphiteTabs(listOf("Обзор", "Файлы", "Пиры", "Трекеры", "Куски"), 1, {})
            GraphiteBottomBar(
                listOf(
                    NavItem("downloads", GraphiteIcons.Download, "Загрузки"),
                    NavItem("stats", GraphiteIcons.ChartLine, "Статистика"),
                    NavItem("settings", GraphiteIcons.Settings, "Настройки"),
                ),
                "downloads",
                {},
            )
            Row(Modifier.height(240.dp)) {
                GraphiteSidebar(
                    listOf(NavItem("downloads", GraphiteIcons.Download, "Загрузки"), NavItem("stats", GraphiteIcons.ChartLine, "Статистика")),
                    "downloads", expanded = true, onToggleExpanded = {}, onSelect = {}, expandLabel = "Меню",
                )
            }
        }
    }
}
