package com.graphite.torrent.ui.app.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.PeerSource
import com.graphite.torrent.core.model.PieceState
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.core.model.TrackerStatus
import com.graphite.torrent.ui.app.common.FileTree
import com.graphite.torrent.ui.app.common.FileTreeNode
import com.graphite.torrent.ui.app.common.adviceRes
import com.graphite.torrent.ui.app.common.bytesText
import com.graphite.torrent.ui.app.common.errorMessage
import com.graphite.torrent.ui.app.common.labelRes
import com.graphite.torrent.ui.app.common.speedText
import com.graphite.torrent.ui.app.common.titleRes
import com.graphite.torrent.ui.app.common.toVisual
import com.graphite.torrent.ui.app.common.units
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.ButtonKind
import com.graphite.torrent.ui.designsystem.components.ConfirmSheet
import com.graphite.torrent.ui.designsystem.components.DetailRow
import com.graphite.torrent.ui.designsystem.components.GraphiteButton
import com.graphite.torrent.ui.designsystem.components.GraphiteCard
import com.graphite.torrent.ui.designsystem.components.GraphiteCheckbox
import com.graphite.torrent.ui.designsystem.components.GraphiteIconButton
import com.graphite.torrent.ui.designsystem.components.GraphiteMenu
import com.graphite.torrent.ui.designsystem.components.GraphiteSheet
import com.graphite.torrent.ui.designsystem.components.GraphiteTabs
import com.graphite.torrent.ui.designsystem.components.GraphiteTextField
import com.graphite.torrent.ui.designsystem.components.InfoRow
import com.graphite.torrent.ui.designsystem.components.MenuAction
import com.graphite.torrent.ui.designsystem.components.graphiteClickable
import com.graphite.torrent.ui.designsystem.components.PieceMap
import com.graphite.torrent.ui.designsystem.components.RingProgress
import com.graphite.torrent.ui.designsystem.components.SkeletonBlock
import com.graphite.torrent.ui.designsystem.components.StatusIcon
import com.graphite.torrent.ui.designsystem.components.SwitchSettingRow
import com.graphite.torrent.ui.designsystem.components.TagChip
import com.graphite.torrent.ui.designsystem.components.TreeRow
import com.graphite.torrent.ui.designsystem.format.Formatters
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DetailsScreen(
    viewModel: DetailsViewModel,
    onBack: (() -> Unit)?,
    onShowMessage: (String) -> Unit,
    onPlay: (fileIndex: Int, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item by viewModel.item.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val spacing = GraphiteTheme.spacing
    var confirmDelete by remember { mutableStateOf(false) }
    var editTags by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val copied = stringResource(Res.string.copied)

    LaunchedEffect(Unit) {
        viewModel.errors.collect { error ->
            onShowMessage(getString(error.kind.titleRes()) + ". " + getString(error.kind.adviceRes()))
        }
    }

    val current = item
    if (current == null) {
        Column(modifier.fillMaxSize().padding(spacing.lg)) {
            if (onBack != null) GraphiteIconButton(GraphiteIcons.ArrowLeft, stringResource(Res.string.back), onBack)
            Spacer(Modifier.height(spacing.lg))
            Text(stringResource(Res.string.torrent_removed), style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary)
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = spacing.xs, vertical = spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) GraphiteIconButton(GraphiteIcons.ArrowLeft, stringResource(Res.string.back), onBack)
            Spacer(Modifier.weight(1f))
            if (current.state != TorrentState.COMPLETED && current.finalLocation == null) {
                val paused = current.state == TorrentState.PAUSED || current.state == TorrentState.ERROR
                GraphiteIconButton(
                    if (paused) GraphiteIcons.Play else GraphiteIcons.Pause,
                    stringResource(if (paused) Res.string.resume else Res.string.pause),
                    viewModel::togglePause,
                )
            }
            GraphiteIconButton(GraphiteIcons.FolderOpen, stringResource(Res.string.open_folder), viewModel::openFolder)
            Box {
                GraphiteIconButton(GraphiteIcons.EllipsisVertical, stringResource(Res.string.more), { menu = true })
                val live = current.finalLocation == null
                GraphiteMenu(
                    menu,
                    { menu = false },
                    buildList {
                        if (live) add(MenuAction(stringResource(Res.string.recheck), GraphiteIcons.RefreshCw, onClick = viewModel::recheck))
                        if (live) add(MenuAction(stringResource(Res.string.move_files), GraphiteIcons.FolderInput, onClick = viewModel::moveTo))
                        if (live) add(MenuAction(stringResource(Res.string.reannounce), GraphiteIcons.RadioTower, onClick = viewModel::reannounce))
                        if (current.magnetUri.isNotBlank()) add(MenuAction(stringResource(Res.string.copy_magnet), GraphiteIcons.Link) {
                            viewModel.copyMagnet()
                            onShowMessage(copied)
                        })
                        if (live && current.hasMetadata) add(MenuAction(stringResource(Res.string.export_torrent), GraphiteIcons.FileDown, onClick = viewModel::exportTorrent))
                        add(MenuAction(stringResource(Res.string.edit_tags), GraphiteIcons.Tags) { editTags = true })
                        add(MenuAction(stringResource(Res.string.delete), GraphiteIcons.Trash2, danger = true) { confirmDelete = true })
                    },
                )
            }
        }

        // Header: ring progress, name, status
        Row(Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            RingProgress(current.progress, current.state.toVisual(), size = 72.dp) {
                Text(Formatters.percent(current.progress, units()), style = GraphiteTheme.typography.numberCaption)
            }
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    current.name,
                    style = GraphiteTheme.typography.itemTitle,
                    maxLines = 3,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(spacing.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(current.state.toVisual())
                    Spacer(Modifier.width(6.dp))
                    val parts = buildList {
                        add(stringResource(current.state.labelRes()))
                        if (current.state == TorrentState.DOWNLOADING) add(speedText(current.downloadRate))
                        if (current.state == TorrentState.DOWNLOADING && current.etaSeconds >= 0) add(Formatters.duration(current.etaSeconds, units()))
                    }
                    Text(parts.joinToString(" · "), style = GraphiteTheme.typography.numberCaption, color = GraphiteTheme.colors.textSecondary)
                }
                current.error?.let {
                    Spacer(Modifier.height(spacing.xxs))
                    Text(errorMessage(it), style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.errorText)
                }
            }
        }

        val tabs = listOf(
            stringResource(Res.string.tab_overview),
            stringResource(Res.string.tab_files),
            stringResource(Res.string.tab_peers),
            stringResource(Res.string.tab_trackers),
            stringResource(Res.string.tab_pieces),
        )
        GraphiteTabs(tabs, tab.ordinal, { viewModel.selectTab(DetailsTab.entries[it]) }, Modifier.padding(horizontal = spacing.xs))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                DetailsTab.OVERVIEW -> OverviewTab(current, viewModel)
                DetailsTab.FILES -> FilesTab(viewModel, onPlay)
                DetailsTab.PEERS -> PeersTab(viewModel)
                DetailsTab.TRACKERS -> TrackersTab(viewModel)
                DetailsTab.PIECES -> PiecesTab(viewModel)
            }
        }
    }

    if (confirmDelete) {
        var withFiles by remember { mutableStateOf(false) }
        ConfirmSheet(
            title = stringResource(Res.string.delete_one_title),
            message = current.name,
            confirmLabel = stringResource(if (withFiles) Res.string.delete_with_files else Res.string.delete_keep_files),
            cancelLabel = stringResource(Res.string.cancel),
            onConfirm = {
                confirmDelete = false
                viewModel.remove(withFiles) { onBack?.invoke() }
            },
            onDismiss = { confirmDelete = false },
            extra = {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    GraphiteCheckbox(if (withFiles) ToggleableState.On else ToggleableState.Off, { withFiles = !withFiles })
                    Text(stringResource(Res.string.delete_files_too), style = GraphiteTheme.typography.body)
                }
            },
        )
    }

    if (editTags) {
        TagsEditor(current, viewModel) { editTags = false }
    }
}

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

internal fun formatDate(millis: Long): String =
    if (millis <= 0) "—" else dateFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

@Composable
private fun OverviewTab(item: TorrentItem, viewModel: DetailsViewModel) {
    val spacing = GraphiteTheme.spacing
    val u = units()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item {
            GraphiteCard {
                InfoRow(stringResource(Res.string.info_downloaded), stringResource(Res.string.size_of, Formatters.bytes(item.doneBytes, u), Formatters.bytes(item.wantedBytes, u)))
                InfoRow(stringResource(Res.string.info_total_size), if (item.totalBytes > 0) Formatters.bytes(item.totalBytes, u) else "—")
                InfoRow(stringResource(Res.string.info_download_speed), Formatters.speed(item.downloadRate, u))
                val uploaded = Formatters.bytes(item.uploadedPayload, u)
                InfoRow(
                    stringResource(Res.string.info_uploaded_payload),
                    if (item.uploadedPayload == 0L) "$uploaded ✓ " + stringResource(Res.string.zero_upload_confirmed) else uploaded,
                )
                InfoRow(stringResource(Res.string.info_eta), if (item.etaSeconds >= 0) Formatters.duration(item.etaSeconds, u) else "—")
                InfoRow(stringResource(Res.string.info_peers), stringResource(Res.string.peers_value, item.connectedPeers, item.totalPeers))
                InfoRow(stringResource(Res.string.info_seeds), stringResource(Res.string.peers_value, item.connectedSeeds, item.totalSeeds))
                if (item.queuePosition >= 0) InfoRow(stringResource(Res.string.info_queue), (item.queuePosition + 1).toString())
            }
        }
        item {
            GraphiteCard {
                InfoRow(stringResource(Res.string.info_folder), item.finalLocation ?: item.savePath, monospace = false)
                InfoRow(stringResource(Res.string.info_added), formatDate(item.addedAt), monospace = false)
                InfoRow(stringResource(Res.string.info_completed), formatDate(item.completedAt), monospace = false)
                InfoRow(stringResource(Res.string.info_hash), item.id)
                if (item.isPrivate) InfoRow(stringResource(Res.string.info_private), stringResource(Res.string.yes), monospace = false)
                if (item.category != null) InfoRow(stringResource(Res.string.info_category), item.category!!, monospace = false)
                if (item.tags.isNotEmpty()) {
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                        item.tags.forEach { TagChip(it, icon = GraphiteIcons.Tag) }
                    }
                }
            }
        }
        if (item.finalLocation == null && item.state != TorrentState.COMPLETED) {
            item {
                GraphiteCard(contentPadding = spacing.xxs) {
                    SwitchSettingRow(
                        stringResource(Res.string.sequential_download),
                        stringResource(Res.string.sequential_download_hint),
                        item.sequential,
                        viewModel::setSequential,
                        icon = GraphiteIcons.ListFilter,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilesTab(viewModel: DetailsViewModel, onPlay: (Int, String) -> Unit) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val list = files
    if (list == null) {
        LoadingRows()
        return
    }
    val tree = remember(list.map { it.path }) { FileTree.build(list) }
    val withProgress = remember(list) { FileTree.build(list) }
    var expanded by remember(tree) { mutableStateOf(if (list.size <= 50) FileTree.allFolders(tree) else emptySet()) }
    val priorities = list.associate { it.index to it.priority }
    val rows = FileTree.flatten(withProgress, expanded)
    val u = units()
    val spacing = GraphiteTheme.spacing
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = spacing.xs, vertical = spacing.xs)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = spacing.xs), horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                GraphiteButton(stringResource(Res.string.select_all_files), { viewModel.setPriorities(list.associate { it.index to FilePriority.NORMAL }) }, kind = ButtonKind.TERTIARY)
                GraphiteButton(stringResource(Res.string.select_no_files), { viewModel.setPriorities(list.associate { it.index to FilePriority.SKIP }) }, kind = ButtonKind.TERTIARY)
            }
        }
        items(rows, key = { it.path + "#" + (it.fileIndex ?: -1) }) { node ->
            FileRow(node, priorities, expanded, onToggleExpand = {
                expanded = if (node.path in expanded) expanded - node.path else expanded + node.path
            }, onCheck = { viewModel.setPriorities(FileTree.toggle(node, priorities)) }, onPriority = { p ->
                viewModel.setPriorities(node.fileIndices.associateWith { p })
            }, onPlay = if (viewModel.streamingAvailable && !node.isFolder && FileTree.isPlayable(node.name)) {
                { onPlay(node.fileIndex!!, node.name) }
            } else {
                null
            }, sizeText = { n ->
                if (n.isFolder) {
                    pluralStringResource(Res.plurals.files_summary, n.fileCount, n.fileCount, Formatters.bytes(n.size, u))
                } else {
                    Formatters.bytes(n.size, u) + " · " + Formatters.percent(n.progress, u)
                }
            })
        }
    }
}

@Composable
private fun FileRow(
    node: FileTreeNode,
    priorities: Map<Int, FilePriority>,
    expanded: Set<String>,
    onToggleExpand: () -> Unit,
    onCheck: () -> Unit,
    onPriority: (FilePriority) -> Unit,
    onPlay: (() -> Unit)?,
    sizeText: @Composable (FileTreeNode) -> String,
) {
    var menu by remember { mutableStateOf(false) }
    val isExpanded = node.path in expanded
    val priority = if (node.isFolder) null else priorities[node.fileIndex] ?: FilePriority.NORMAL
    TreeRow(
        title = node.name,
        subtitle = sizeText(node),
        depth = node.depth,
        icon = FileTree.iconFor(node, isExpanded),
        checkState = FileTree.checkState(node, priorities),
        onCheckedClick = onCheck,
        expanded = if (node.isFolder) isExpanded else null,
        onExpandClick = if (node.isFolder) onToggleExpand else null,
        progress = if (node.isFolder) null else node.progress,
        expandLabel = stringResource(Res.string.expand),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onPlay != null) GraphiteIconButton(GraphiteIcons.Play, stringResource(Res.string.play), onPlay, size = 40.dp)
            Box {
                GraphiteButton(
                    if (priority != null) stringResource(priority.labelRes()) else stringResource(Res.string.priority),
                    { menu = true },
                    kind = ButtonKind.TERTIARY,
                )
                GraphiteMenu(menu, { menu = false }, FilePriority.entries.map { p ->
                    MenuAction(stringResource(p.labelRes()), if (p == priority) GraphiteIcons.Check else null) { onPriority(p) }
                })
            }
        }
    }
}

@Composable
private fun PeersTab(viewModel: DetailsViewModel) {
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val list = peers ?: run { LoadingRows(); return }
    val spacing = GraphiteTheme.spacing
    val u = units()
    if (list.isEmpty()) {
        EmptyTabText(stringResource(Res.string.no_peers))
        return
    }
    val sourceNames = mapOf(
        PeerSource.TRACKER to stringResource(Res.string.source_tracker),
        PeerSource.DHT to "DHT",
        PeerSource.PEX to "PEX",
        PeerSource.LSD to "LSD",
        PeerSource.RESUME to stringResource(Res.string.source_resume),
        PeerSource.INCOMING to stringResource(Res.string.source_incoming),
        PeerSource.WEB_SEED to stringResource(Res.string.source_web_seed),
    )
    val encrypted = stringResource(Res.string.peer_encrypted)
    val seed = stringResource(Res.string.peer_seed)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs)) {
        item {
            Text(stringResource(Res.string.peers_count, list.size), style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
        }
        items(list, key = { it.address }) { p ->
            val flags = buildList {
                add(p.client)
                addAll(p.sources.mapNotNull { sourceNames[it] })
                if (p.isUtp) add("µTP")
                if (p.isEncrypted) add(encrypted)
                if (p.isSeed) add(seed)
            }
            DetailRow(
                title = p.address,
                subtitle = flags.joinToString(" · "),
                value = "↓ " + Formatters.speed(p.downloadRate, u),
                secondaryValue = Formatters.percent(p.progress, u),
                icon = if (p.isSeed) GraphiteIcons.Server else GraphiteIcons.Users,
            )
        }
    }
}

@Composable
private fun TrackersTab(viewModel: DetailsViewModel) {
    val trackers by viewModel.trackers.collectAsStateWithLifecycle()
    val list = trackers ?: run { LoadingRows(); return }
    val spacing = GraphiteTheme.spacing
    val colors = GraphiteTheme.colors
    var newUrl by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GraphiteTextField(
                    newUrl,
                    { newUrl = it },
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(Res.string.add_tracker_hint),
                    leadingIcon = GraphiteIcons.RadioTower,
                    onImeAction = {
                        if (newUrl.isNotBlank()) viewModel.addTracker(newUrl)
                        newUrl = ""
                    },
                )
                Spacer(Modifier.width(spacing.xs))
                GraphiteIconButton(GraphiteIcons.Plus, stringResource(Res.string.add_tracker), {
                    if (newUrl.isNotBlank()) viewModel.addTracker(newUrl)
                    newUrl = ""
                })
            }
            Spacer(Modifier.height(spacing.xs))
        }
        items(list, key = { it.url }) { t ->
            var menu by remember { mutableStateOf(false) }
            val statusText = when (t.status) {
                TrackerStatus.WORKING -> stringResource(Res.string.tracker_working)
                TrackerStatus.UPDATING -> stringResource(Res.string.tracker_updating)
                TrackerStatus.ERROR -> stringResource(Res.string.tracker_error)
                TrackerStatus.NOT_CONTACTED -> stringResource(Res.string.tracker_not_contacted)
                TrackerStatus.DISABLED -> stringResource(Res.string.tracker_disabled)
            }
            Box {
                DetailRow(
                    title = t.url,
                    subtitle = listOf(statusText, t.message).filter { it.isNotBlank() }.joinToString(" · "),
                    value = if (t.isPseudo) "" else stringResource(Res.string.tracker_counts, t.seeds, t.peers),
                    icon = when (t.status) {
                        TrackerStatus.WORKING -> GraphiteIcons.CircleCheck
                        TrackerStatus.ERROR -> GraphiteIcons.CircleAlert
                        TrackerStatus.UPDATING -> GraphiteIcons.RefreshCw
                        else -> GraphiteIcons.Clock
                    },
                    iconTint = if (t.status == TrackerStatus.ERROR) colors.error else colors.textSecondary,
                    modifier = if (!t.isPseudo) {
                        Modifier.graphiteClickable(
                            GraphiteTheme.shapes.control,
                            baseColor = Color.Transparent,
                            onLongClick = { menu = true },
                            onClick = { menu = true },
                        )
                    } else {
                        Modifier
                    },
                )
                if (!t.isPseudo) {
                    GraphiteMenu(menu, { menu = false }, listOf(
                        MenuAction(stringResource(Res.string.remove_tracker), GraphiteIcons.Trash2, danger = true) { viewModel.removeTracker(t.url) },
                    ))
                }
            }
        }
    }
}

@Composable
private fun PiecesTab(viewModel: DetailsViewModel) {
    val pieces by viewModel.pieces.collectAsStateWithLifecycle()
    val snapshot = pieces ?: run { LoadingRows(); return }
    val spacing = GraphiteTheme.spacing
    val colors = GraphiteTheme.colors
    if (snapshot.pieceCount == 0) {
        EmptyTabText(stringResource(Res.string.no_metadata_yet))
        return
    }
    val counts = IntArray(4)
    snapshot.states.forEach { counts[it.toInt().coerceIn(0, 3)]++ }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item {
            Text(
                stringResource(Res.string.pieces_summary, snapshot.pieceCount, bytesText(snapshot.pieceSize.toLong())),
                style = GraphiteTheme.typography.caption,
                color = colors.textSecondary,
            )
        }
        item {
            PieceMap(snapshot.states, contentDescription = stringResource(Res.string.pieces_description, counts[3], snapshot.pieceCount))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Legend(colors.accent, stringResource(Res.string.piece_done), counts[PieceState.DONE.toInt()])
                Legend(colors.textSecondary, stringResource(Res.string.piece_downloading), counts[PieceState.DOWNLOADING.toInt()])
                Legend(colors.borderStrong, stringResource(Res.string.piece_queued), counts[PieceState.QUEUED.toInt()])
                Legend(colors.border, stringResource(Res.string.piece_missing), counts[PieceState.MISSING.toInt()])
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(GraphiteTheme.shapes.control).background(color))
        Spacer(Modifier.width(GraphiteTheme.spacing.xs))
        Text(label, style = GraphiteTheme.typography.body, modifier = Modifier.weight(1f))
        Text(count.toString(), style = GraphiteTheme.typography.numberCaption, color = GraphiteTheme.colors.textSecondary)
    }
}

@Composable
private fun LoadingRows() {
    Column(Modifier.fillMaxWidth().padding(GraphiteTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(GraphiteTheme.spacing.sm)) {
        repeat(5) { SkeletonBlock(Modifier.fillMaxWidth().height(36.dp)) }
    }
}

@Composable
private fun EmptyTabText(text: String) {
    Box(Modifier.fillMaxSize().padding(GraphiteTheme.spacing.lg), contentAlignment = Alignment.TopCenter) {
        Text(text, style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary)
    }
}

@Composable
private fun TagsEditor(item: TorrentItem, viewModel: DetailsViewModel, onDismiss: () -> Unit) {
    val categories by viewModel.categories.collectAsStateWithLifecycle(emptyList())
    var category by remember { mutableStateOf(item.category.orEmpty()) }
    var tags by remember { mutableStateOf(item.tags.joinToString(", ")) }
    GraphiteSheet(onDismiss = onDismiss, title = stringResource(Res.string.edit_tags)) {
        GraphiteTextField(category, { category = it }, label = stringResource(Res.string.info_category), leadingIcon = GraphiteIcons.Layers)
        if (categories.isNotEmpty()) {
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.take(6).forEach { c ->
                    com.graphite.torrent.ui.designsystem.components.FilterChip(c.name, c.name == category, { category = c.name })
                }
            }
        }
        Spacer(Modifier.height(GraphiteTheme.spacing.sm))
        GraphiteTextField(tags, { tags = it }, label = stringResource(Res.string.tags_hint), leadingIcon = GraphiteIcons.Tag)
        Spacer(Modifier.height(GraphiteTheme.spacing.lg))
        com.graphite.torrent.ui.designsystem.components.ButtonRow(Modifier.fillMaxWidth()) {
            GraphiteButton(stringResource(Res.string.cancel), onDismiss, kind = ButtonKind.SECONDARY)
            GraphiteButton(stringResource(Res.string.save), {
                viewModel.setCategory(category.trim().ifEmpty { null })
                viewModel.setTags(tags.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                onDismiss()
            })
        }
    }
}
