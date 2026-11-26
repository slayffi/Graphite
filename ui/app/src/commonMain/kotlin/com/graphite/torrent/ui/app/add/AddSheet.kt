package com.graphite.torrent.ui.app.add

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graphite.torrent.ui.app.common.FileTree
import com.graphite.torrent.ui.app.common.adviceRes
import com.graphite.torrent.ui.app.common.titleRes
import com.graphite.torrent.ui.app.common.units
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.ButtonKind
import com.graphite.torrent.ui.designsystem.components.ButtonRow
import com.graphite.torrent.ui.designsystem.components.FilterChip
import com.graphite.torrent.ui.designsystem.components.GraphiteButton
import com.graphite.torrent.ui.designsystem.components.GraphiteCard
import com.graphite.torrent.ui.designsystem.components.GraphiteSheet
import com.graphite.torrent.ui.designsystem.components.GraphiteTextField
import com.graphite.torrent.ui.designsystem.components.SectionHeader
import com.graphite.torrent.ui.designsystem.components.SkeletonBlock
import com.graphite.torrent.ui.designsystem.components.SwitchSettingRow
import com.graphite.torrent.ui.designsystem.components.TreeRow
import com.graphite.torrent.ui.designsystem.components.graphiteClickable
import com.graphite.torrent.ui.designsystem.format.Formatters
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Add flow: choose a source (file / link / QR), preview the content, pick files and folder. */
@Composable
fun AddSheet(viewModel: AddViewModel, onDismiss: () -> Unit) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    LaunchedEffect(step) { if (step is AddStep.Done) onDismiss() }
    val title = when (step) {
        is AddStep.Preview -> stringResource(Res.string.add_preview_title)
        else -> stringResource(Res.string.add_download)
    }
    GraphiteSheet(onDismiss = onDismiss, title = title) {
        when (val s = step) {
            AddStep.Choose -> ChooseSource(viewModel)
            is AddStep.Loading -> LoadingMetadata(s, viewModel, onDismiss)
            is AddStep.Preview -> PreviewContent(s, viewModel, onDismiss)
            is AddStep.Failed -> FailedContent(s, viewModel)
            AddStep.Done -> Unit
        }
    }
}

@Composable
private fun ChooseSource(viewModel: AddViewModel) {
    val spacing = GraphiteTheme.spacing
    val link by viewModel.link.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboardMagnet.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        clipboard?.let { magnet ->
            GraphiteCard(onClick = { viewModel.openMagnet(magnet) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(GraphiteIcons.ClipboardPaste, null, tint = GraphiteTheme.colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(spacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.clipboard_magnet), style = GraphiteTheme.typography.label)
                        Text(magnet, style = GraphiteTheme.typography.numberCaption, color = GraphiteTheme.colors.textSecondary, maxLines = 1)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            SourceTile(GraphiteIcons.FilePlus, stringResource(Res.string.source_file), Modifier.weight(1f), viewModel::pickFile)
            if (viewModel.canScanQr) SourceTile(GraphiteIcons.QrCode, stringResource(Res.string.source_qr), Modifier.weight(1f), viewModel::scanQr)
        }
        SectionHeader(stringResource(Res.string.source_link))
        GraphiteTextField(
            link,
            viewModel::setLink,
            placeholder = "magnet:?xt=urn:btih:…",
            leadingIcon = GraphiteIcons.Link,
            singleLine = false,
            minLines = 2,
            onImeAction = viewModel::submitLink,
        )
        ButtonRow(Modifier.fillMaxWidth()) {
            GraphiteButton(stringResource(Res.string.continue_label), viewModel::submitLink, enabled = link.isNotBlank(), icon = GraphiteIcons.ArrowDown)
        }
    }
}

@Composable
private fun SourceTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = GraphiteTheme.colors
    Column(
        modifier
            .height(96.dp)
            .graphiteClickable(GraphiteTheme.shapes.card, baseColor = colors.surface1, onClick = onClick)
            .padding(GraphiteTheme.spacing.md),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, null, tint = colors.textPrimary, modifier = Modifier.size(24.dp))
        Text(label, style = GraphiteTheme.typography.label)
    }
}

@Composable
private fun LoadingMetadata(step: AddStep.Loading, viewModel: AddViewModel, onDismiss: () -> Unit) {
    val spacing = GraphiteTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(step.label, style = GraphiteTheme.typography.itemTitle, maxLines = 2)
        Text(stringResource(Res.string.fetching_metadata), style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary)
        repeat(4) { SkeletonBlock(Modifier.fillMaxWidth(0.9f - it * 0.15f).height(18.dp)) }
        Spacer(Modifier.height(spacing.sm))
        ButtonRow(Modifier.fillMaxWidth()) {
            GraphiteButton(stringResource(Res.string.cancel), onDismiss, kind = ButtonKind.SECONDARY)
            if (step.magnet != null) GraphiteButton(stringResource(Res.string.add_without_preview), viewModel::addWithoutPreview)
        }
    }
}

@Composable
private fun PreviewContent(step: AddStep.Preview, viewModel: AddViewModel, onDismiss: () -> Unit) {
    val options by viewModel.options.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle(emptyList())
    val spacing = GraphiteTheme.spacing
    val colors = GraphiteTheme.colors
    val u = units()
    val meta = step.metadata
    val selectedSize = FileTree.selectedSize(step.tree, options.priorities)
    val notEnough = options.freeSpace in 0 until selectedSize

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(meta.name, style = GraphiteTheme.typography.itemTitle, maxLines = 3)
        Text(
            pluralStringResource(Res.plurals.files_summary, meta.files.size, meta.files.size, Formatters.bytes(meta.totalSize, u)) +
                if (meta.isPrivate) " · " + stringResource(Res.string.info_private) else "",
            style = GraphiteTheme.typography.numberCaption,
            color = colors.textSecondary,
        )

        // Folder + free space
        GraphiteCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(GraphiteIcons.Folder, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.save_to), style = GraphiteTheme.typography.caption, color = colors.textSecondary)
                    Text(options.displayPath, style = GraphiteTheme.typography.body, maxLines = 2)
                }
                GraphiteButton(stringResource(Res.string.change), viewModel::chooseFolder, kind = ButtonKind.TERTIARY)
            }
            if (options.freeSpace >= 0) {
                Spacer(Modifier.height(spacing.sm))
                SpaceIndicator(selectedSize, options.freeSpace)
                Spacer(Modifier.height(spacing.xxs))
                Text(
                    if (notEnough) stringResource(Res.string.not_enough_space, Formatters.bytes(selectedSize, u), Formatters.bytes(options.freeSpace, u))
                    else stringResource(Res.string.space_needed, Formatters.bytes(selectedSize, u), Formatters.bytes(options.freeSpace, u)),
                    style = GraphiteTheme.typography.numberCaption,
                    color = if (notEnough) colors.errorText else colors.textSecondary,
                )
            }
            if (options.staged) {
                LifecycleResumeEffect(Unit) {
                    viewModel.refreshAccess()
                    onPauseOrDispose {}
                }
                Spacer(Modifier.height(spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(GraphiteIcons.Info, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(spacing.xs))
                    Text(
                        stringResource(Res.string.add_staged_hint),
                        style = GraphiteTheme.typography.caption,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    GraphiteButton(stringResource(Res.string.allow), viewModel::requestAllFilesAccess, kind = ButtonKind.TERTIARY)
                }
            }
        }

        // Files
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(Res.string.tab_files), Modifier.weight(1f))
            GraphiteButton(stringResource(Res.string.select_all_files), { viewModel.selectAll(true) }, kind = ButtonKind.TERTIARY)
            GraphiteButton(stringResource(Res.string.select_no_files), { viewModel.selectAll(false) }, kind = ButtonKind.TERTIARY)
        }
        GraphiteCard(contentPadding = spacing.xxs) {
            FileTree.flatten(step.tree, options.expanded).take(MAX_PREVIEW_ROWS).forEach { node ->
                val expanded = node.path in options.expanded
                TreeRow(
                    title = node.name,
                    subtitle = if (node.isFolder) pluralStringResource(Res.plurals.files_summary, node.fileCount, node.fileCount, Formatters.bytes(node.size, u)) else Formatters.bytes(node.size, u),
                    depth = node.depth,
                    icon = FileTree.iconFor(node, expanded),
                    checkState = FileTree.checkState(node, options.priorities),
                    onCheckedClick = { viewModel.toggle(node) },
                    expanded = if (node.isFolder) expanded else null,
                    onExpandClick = if (node.isFolder) ({ viewModel.toggleExpanded(node.path) }) else null,
                    expandLabel = stringResource(Res.string.expand),
                )
            }
        }

        // Options
        GraphiteTextField(options.category, viewModel::setCategory, label = stringResource(Res.string.info_category), leadingIcon = GraphiteIcons.Layers)
        if (categories.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                categories.take(5).forEach { c -> FilterChip(c.name, c.name == options.category, { viewModel.setCategory(c.name) }) }
            }
        }
        GraphiteCard(contentPadding = spacing.xxs) {
            SwitchSettingRow(stringResource(Res.string.sequential_download), stringResource(Res.string.sequential_download_hint), options.sequential, viewModel::setSequential)
            SwitchSettingRow(stringResource(Res.string.start_paused), null, options.startPaused, viewModel::setStartPaused)
        }
        Spacer(Modifier.height(spacing.xs))
        ButtonRow(Modifier.fillMaxWidth()) {
            GraphiteButton(stringResource(Res.string.cancel), onDismiss, kind = ButtonKind.SECONDARY)
            GraphiteButton(stringResource(Res.string.download), viewModel::confirm, icon = GraphiteIcons.Download, enabled = selectedSize > 0)
        }
    }
}

@Composable
private fun SpaceIndicator(needed: Long, free: Long) {
    val colors = GraphiteTheme.colors
    val fraction = if (free <= 0) 1f else (needed.toDouble() / free).toFloat().coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(GraphiteTheme.shapes.pill).background(colors.border)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceAtLeast(0.01f))
                .fillMaxHeight()
                .clip(GraphiteTheme.shapes.pill)
                .background(if (needed > free) colors.error else colors.accent),
        )
    }
}

@Composable
private fun FailedContent(step: AddStep.Failed, viewModel: AddViewModel) {
    val colors = GraphiteTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(GraphiteTheme.spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(GraphiteIcons.TriangleAlert, null, tint = colors.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(GraphiteTheme.spacing.xs))
            Text(stringResource(step.error.kind.titleRes()), style = GraphiteTheme.typography.itemTitle, color = colors.errorText)
        }
        Text(stringResource(step.error.kind.adviceRes()), style = GraphiteTheme.typography.body, color = colors.textSecondary)
        if (step.error.detail.isNotBlank()) {
            Text(step.error.detail, style = GraphiteTheme.typography.numberCaption, color = colors.textDisabled, maxLines = 3)
        }
        ButtonRow(Modifier.fillMaxWidth()) {
            GraphiteButton(stringResource(Res.string.back), viewModel::reset, kind = ButtonKind.SECONDARY)
            step.retry?.let { GraphiteButton(stringResource(Res.string.retry), it) }
        }
    }
}

private const val MAX_PREVIEW_ROWS = 400
