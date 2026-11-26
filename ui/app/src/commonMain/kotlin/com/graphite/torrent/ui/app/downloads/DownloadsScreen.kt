package com.graphite.torrent.ui.app.downloads

import androidx.compose.animation.AnimatedVisibility
import com.graphite.torrent.ui.app.common.rememberAppScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.ui.app.common.adviceRes
import com.graphite.torrent.ui.app.common.titleRes
import com.graphite.torrent.ui.app.common.speedText
import com.graphite.torrent.ui.app.common.toCardState
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.ButtonKind
import com.graphite.torrent.ui.designsystem.components.ConfirmSheet
import com.graphite.torrent.ui.designsystem.components.EmptyState
import com.graphite.torrent.ui.designsystem.components.FilterChip
import com.graphite.torrent.ui.designsystem.components.GraphiteButton
import com.graphite.torrent.ui.designsystem.components.GraphiteCheckbox
import com.graphite.torrent.ui.designsystem.components.GraphiteFab
import com.graphite.torrent.ui.designsystem.components.GraphiteIconButton
import com.graphite.torrent.ui.designsystem.components.GraphiteMenu
import com.graphite.torrent.ui.designsystem.components.MenuAction
import com.graphite.torrent.ui.designsystem.components.SearchField
import com.graphite.torrent.ui.designsystem.components.SkeletonTorrentCard
import com.graphite.torrent.ui.designsystem.components.SummaryHeader
import com.graphite.torrent.ui.designsystem.components.TorrentCard
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.platform.isDesktop
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import androidx.compose.ui.state.ToggleableState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onOpenDetails: (TorrentItem) -> Unit,
    onAdd: () -> Unit,
    onShowError: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val history by viewModel.speedHistory.collectAsStateWithLifecycle()
    val spacing = GraphiteTheme.spacing
    var searchVisible by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<List<TorrentItem>?>(null) }
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { error ->
            onShowError(getString(error.kind.titleRes()) + ". " + getString(error.kind.adviceRes()))
        }
    }

    val selectionCount = state.selection.size
    Column(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.isCtrlPressed && event.key == Key.F -> {
                        searchVisible = true
                        true
                    }
                    event.isCtrlPressed && event.key == Key.A && state.items.isNotEmpty() -> {
                        viewModel.selectAll()
                        true
                    }
                    event.key == Key.Escape && state.selectionMode -> {
                        viewModel.clearSelection()
                        true
                    }
                    event.key == Key.Delete && state.selectionMode -> {
                        deleteTarget = state.items.filter { it.id in state.selection }
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Top bar: title or selection actions
        if (state.selectionMode) {
            SelectionBar(
                count = selectionCount,
                onClose = viewModel::clearSelection,
                onSelectAll = viewModel::selectAll,
                onPause = { viewModel.pause(state.selection) },
                onResume = { viewModel.resume(state.selection) },
                onDelete = { deleteTarget = state.items.filter { it.id in state.selection } },
            )
        } else {
            TitleBar(
                onSearch = {
                    searchVisible = !searchVisible
                    if (!searchVisible) viewModel.setQuery("")
                },
                sort = state.filters.sort,
                onSort = viewModel::setSort,
                categories = state.categories,
                tags = state.tags,
                activeCategory = state.filters.category,
                activeTag = state.filters.tag,
                onCategory = viewModel::setCategory,
                onTag = viewModel::setTag,
                onPauseAll = viewModel::pauseAll,
                onResumeAll = viewModel::resumeAll,
            )
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = spacing.md, end = spacing.md, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                item(key = "summary") {
                    val u = GraphiteTheme.units
                    SummaryHeader(
                        speedText = speedText(session.downloadRate),
                        speedLabel = stringResource(Res.string.total_speed),
                        history = history.takeLast(60).map { it.downloadRate },
                        activeText = stringResource(Res.string.active_of_total, session.activeTorrents, state.totalCount),
                        uploadText = stringResource(Res.string.upload_zero_line, com.graphite.torrent.ui.designsystem.format.Formatters.bytes(session.sessionUploaded, u)) +
                            if (session.sessionUploaded == 0L) " ✓" else "",
                        chartDescription = stringResource(Res.string.speed_chart_description),
                        valueLabel = { com.graphite.torrent.ui.designsystem.format.Formatters.speed(it, u) },
                    )
                }
                item(key = "filters") {
                    Column {
                        AnimatedVisibility(searchVisible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            SearchField(
                                state.filters.query,
                                viewModel::setQuery,
                                placeholder = stringResource(Res.string.search_downloads),
                                clearLabel = stringResource(Res.string.clear),
                                modifier = Modifier.padding(top = spacing.sm).focusRequester(searchFocus),
                            )
                            LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            StatusFilter.entries.forEach { f ->
                                FilterChip(
                                    label = stringResource(f.labelRes()),
                                    selected = state.filters.status == f,
                                    onClick = { viewModel.setStatusFilter(f) },
                                    count = state.counts[f],
                                )
                            }
                            if (state.filters.category != null) {
                                FilterChip(state.filters.category!!, true, { viewModel.setCategory(null) }, icon = GraphiteIcons.Layers)
                            }
                            if (state.filters.tag != null) {
                                FilterChip(state.filters.tag!!, true, { viewModel.setTag(null) }, icon = GraphiteIcons.Tag)
                            }
                        }
                    }
                }
                when {
                    state.loading -> items(4, key = { "skeleton$it" }) { SkeletonTorrentCard() }
                    state.items.isEmpty() && state.totalCount == 0 -> item(key = "empty") {
                        EmptyState(
                            title = stringResource(Res.string.empty_title),
                            message = stringResource(Res.string.empty_message),
                            actionLabel = stringResource(Res.string.add_download),
                            onAction = onAdd,
                        )
                    }
                    state.items.isEmpty() -> item(key = "nothing") {
                        Text(
                            stringResource(Res.string.nothing_found),
                            style = GraphiteTheme.typography.body,
                            color = GraphiteTheme.colors.textSecondary,
                            modifier = Modifier.padding(spacing.lg),
                        )
                    }
                    else -> items(state.items, key = { it.id }) { item ->
                        TorrentRow(
                            item = item,
                            listState = listState,
                            viewModel = viewModel,
                            selected = item.id in state.selection || item.id == selectedId,
                            selectionMode = state.selectionMode,
                            queueMode = state.filters.sort == SortOrder.QUEUE,
                            onOpen = onOpenDetails,
                            onDelete = { deleteTarget = listOf(it) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(GraphiteTheme.motion.duration(GraphiteTheme.motion.medium)),
                                placementSpec = tween(GraphiteTheme.motion.duration(GraphiteTheme.motion.medium), easing = GraphiteTheme.motion.easing),
                                fadeOutSpec = tween(GraphiteTheme.motion.duration(GraphiteTheme.motion.short)),
                            ),
                        )
                    }
                }
            }
            if (!state.selectionMode) {
                GraphiteFab(
                    GraphiteIcons.Plus,
                    stringResource(Res.string.add_download),
                    onAdd,
                    Modifier.align(Alignment.BottomEnd).padding(spacing.lg),
                )
            }
        }
    }

    deleteTarget?.let { targets ->
        var withFiles by remember(targets) { mutableStateOf(false) }
        ConfirmSheet(
            title = pluralStringResource(Res.plurals.delete_title, targets.size, targets.size),
            message = if (targets.size == 1) targets.first().name else stringResource(Res.string.delete_message_many),
            confirmLabel = stringResource(if (withFiles) Res.string.delete_with_files else Res.string.delete_keep_files),
            cancelLabel = stringResource(Res.string.cancel),
            onConfirm = {
                viewModel.remove(targets.map { it.id }, withFiles)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            extra = {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GraphiteCheckbox(if (withFiles) ToggleableState.On else ToggleableState.Off, { withFiles = !withFiles })
                    Text(stringResource(Res.string.delete_files_too), style = GraphiteTheme.typography.body)
                }
            },
        )
    }
}

private fun StatusFilter.labelRes() = when (this) {
    StatusFilter.ALL -> Res.string.filter_all
    StatusFilter.ACTIVE -> Res.string.filter_active
    StatusFilter.PAUSED -> Res.string.filter_paused
    StatusFilter.COMPLETED -> Res.string.filter_completed
}

private fun SortOrder.labelRes() = when (this) {
    SortOrder.ADDED -> Res.string.sort_added
    SortOrder.NAME -> Res.string.sort_name
    SortOrder.PROGRESS -> Res.string.sort_progress
    SortOrder.SPEED -> Res.string.sort_speed
    SortOrder.SIZE -> Res.string.sort_size
    SortOrder.QUEUE -> Res.string.sort_queue
}

@Composable
private fun TitleBar(
    onSearch: () -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    categories: List<String>,
    tags: List<String>,
    activeCategory: String?,
    activeTag: String?,
    onCategory: (String?) -> Unit,
    onTag: (String?) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    val spacing = GraphiteTheme.spacing
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = spacing.md, end = spacing.xs, top = spacing.sm, bottom = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.tab_downloads), style = GraphiteTheme.typography.screenTitle, modifier = Modifier.weight(1f))
        GraphiteIconButton(GraphiteIcons.Search, stringResource(Res.string.search), onSearch)
        Box {
            GraphiteIconButton(GraphiteIcons.ArrowUpDown, stringResource(Res.string.sort), { sortMenu = true })
            GraphiteMenu(
                expanded = sortMenu,
                onDismiss = { sortMenu = false },
                actions = SortOrder.entries.map { s ->
                    MenuAction(stringResource(s.labelRes()), if (s == sort) GraphiteIcons.Check else null) { onSort(s) }
                },
            )
        }
        Box {
            GraphiteIconButton(GraphiteIcons.EllipsisVertical, stringResource(Res.string.more), { moreMenu = true })
            val actions = buildList {
                add(MenuAction(stringResource(Res.string.pause_all), GraphiteIcons.Pause, onClick = onPauseAll))
                add(MenuAction(stringResource(Res.string.resume_all), GraphiteIcons.Play, onClick = onResumeAll))
                categories.forEach { c ->
                    add(MenuAction(c, if (c == activeCategory) GraphiteIcons.Check else GraphiteIcons.Layers) { onCategory(if (c == activeCategory) null else c) })
                }
                tags.forEach { t ->
                    add(MenuAction("#$t", if (t == activeTag) GraphiteIcons.Check else GraphiteIcons.Tag) { onTag(if (t == activeTag) null else t) })
                }
            }
            GraphiteMenu(expanded = moreMenu, onDismiss = { moreMenu = false }, actions = actions)
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = GraphiteTheme.spacing
    Row(
        Modifier.fillMaxWidth().background(GraphiteTheme.colors.surface1).padding(horizontal = spacing.xs, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GraphiteIconButton(GraphiteIcons.X, stringResource(Res.string.clear_selection), onClose)
        Text(
            pluralStringResource(Res.plurals.selected_count, count, count),
            style = GraphiteTheme.typography.itemTitle,
            modifier = Modifier.weight(1f).padding(start = spacing.xs),
        )
        GraphiteIconButton(GraphiteIcons.CheckCheck, stringResource(Res.string.select_all), onSelectAll)
        GraphiteIconButton(GraphiteIcons.Pause, stringResource(Res.string.pause), onPause)
        GraphiteIconButton(GraphiteIcons.Play, stringResource(Res.string.resume), onResume)
        GraphiteIconButton(GraphiteIcons.Trash2, stringResource(Res.string.delete), onDelete, tint = GraphiteTheme.colors.errorText)
    }
}

/**
 * One list row: swipe right to pause/resume and left to delete (touch), right-click context menu
 * (desktop), long press for multi-select, and a drag handle in queue order.
 */
@Composable
private fun TorrentRow(
    item: TorrentItem,
    listState: LazyListState,
    viewModel: DownloadsViewModel,
    selected: Boolean,
    selectionMode: Boolean,
    queueMode: Boolean,
    onOpen: (TorrentItem) -> Unit,
    onDelete: (TorrentItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GraphiteTheme.colors
    val scope = rememberAppScope()
    val swipe = remember { Animatable(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    var menu by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val paused = item.state == TorrentState.PAUSED || item.state == TorrentState.ERROR
    val pauseLabel = stringResource(if (paused) Res.string.resume else Res.string.pause)
    val deleteLabel = stringResource(Res.string.delete)
    val openLabel = stringResource(Res.string.open_details)

    val actions = buildList {
        if (item.state != TorrentState.COMPLETED) add(MenuAction(pauseLabel, if (paused) GraphiteIcons.Play else GraphiteIcons.Pause) { viewModel.togglePause(item) })
        add(MenuAction(openLabel, GraphiteIcons.Info) { onOpen(item) })
        add(MenuAction(stringResource(Res.string.recheck), GraphiteIcons.RefreshCw) { viewModel.recheck(item.id) })
        if (item.queuePosition >= 0) {
            add(MenuAction(stringResource(Res.string.queue_up), GraphiteIcons.ChevronUp) { viewModel.moveInQueue(item.id, -1) })
            add(MenuAction(stringResource(Res.string.queue_down), GraphiteIcons.ChevronDown) { viewModel.moveInQueue(item.id, 1) })
        }
        add(MenuAction(stringResource(Res.string.select), GraphiteIcons.SquareCheck) { viewModel.toggleSelection(item.id) })
        add(MenuAction(deleteLabel, GraphiteIcons.Trash2, danger = true) { onDelete(item) })
    }

    Box(
        modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(pauseLabel) { viewModel.togglePause(item); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(item); true },
                )
            },
    ) {
        // Swipe backgrounds
        if (abs(swipe.value) > 1f) {
            Row(
                Modifier.matchParentSize().clip(GraphiteTheme.shapes.card).background(colors.surface2).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (swipe.value > 0) {
                    Icon(if (paused) GraphiteIcons.Play else GraphiteIcons.Pause, null, tint = colors.textPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(pauseLabel, style = GraphiteTheme.typography.label)
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                    Text(deleteLabel, style = GraphiteTheme.typography.label, color = colors.errorText)
                    Spacer(Modifier.width(8.dp))
                    Icon(GraphiteIcons.Trash2, null, tint = colors.errorText)
                }
            }
        }
        Row(
            Modifier
                .graphicsLayer { translationX = swipe.value }
                .then(
                    if (!isDesktop && !selectionMode) {
                        Modifier.pointerInput(item.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val v = swipe.value
                                    when {
                                        v > threshold -> viewModel.togglePause(item)
                                        v < -threshold -> onDelete(item)
                                    }
                                    scope.launch { swipe.animateTo(0f, tween(200)) }
                                },
                                onDragCancel = { scope.launch { swipe.animateTo(0f, tween(200)) } },
                            ) { change, amount ->
                                change.consume()
                                scope.launch { swipe.snapTo((swipe.value + amount).coerceIn(-threshold * 1.5f, threshold * 1.5f)) }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .pointerInput(item.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) menu = true
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (queueMode) {
                Box(
                    Modifier
                        .size(32.dp, 48.dp)
                        .pointerInput(item.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = true; dragOffset = 0f },
                                onDragEnd = {
                                    val info = listState.layoutInfo.visibleItemsInfo
                                    val me = info.firstOrNull { it.key == item.id }
                                    if (me != null) {
                                        val center = me.offset + me.size / 2 + dragOffset.roundToInt()
                                        val target = info.firstOrNull { center in it.offset..(it.offset + it.size) && it.key is String && it.key != item.id && it.key != "summary" && it.key != "filters" }
                                        (target?.key as? String)?.let { viewModel.reorder(item.id, it) }
                                    }
                                    dragging = false
                                    dragOffset = 0f
                                },
                                onDragCancel = { dragging = false; dragOffset = 0f },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GraphiteIcons.GripVertical, stringResource(Res.string.drag_to_reorder), tint = colors.textSecondary)
                }
            }
            Box(Modifier.weight(1f)) {
                TorrentCard(
                    state = item.toCardState(),
                    onClick = { if (selectionMode) viewModel.toggleSelection(item.id) else onOpen(item) },
                    selected = selected,
                    selectionMode = selectionMode,
                    onLongClick = { viewModel.toggleSelection(item.id) },
                )
                GraphiteMenu(menu, { menu = false }, actions, offset = DpOffset(24.dp, 0.dp))
            }
        }
    }
}

@Composable
internal fun ErrorBanner(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val colors = GraphiteTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(GraphiteTheme.shapes.control)
            .background(colors.surface2)
            .padding(GraphiteTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(GraphiteIcons.TriangleAlert, null, tint = colors.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(GraphiteTheme.spacing.xs))
        Text(text, style = GraphiteTheme.typography.body, color = colors.errorText, modifier = Modifier.weight(1f))
        if (onRetry != null) GraphiteButton(stringResource(Res.string.retry), onRetry, kind = ButtonKind.TERTIARY)
    }
}
