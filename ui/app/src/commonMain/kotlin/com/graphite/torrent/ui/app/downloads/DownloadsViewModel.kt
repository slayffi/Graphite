package com.graphite.torrent.ui.app.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.core.engine.ErrorMapper
import com.graphite.torrent.ui.app.AppEnvironment
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StatusFilter { ALL, ACTIVE, PAUSED, COMPLETED }

enum class SortOrder { ADDED, NAME, PROGRESS, SPEED, SIZE, QUEUE }

data class ListFilters(
    val status: StatusFilter = StatusFilter.ALL,
    val query: String = "",
    val category: String? = null,
    val tag: String? = null,
    val sort: SortOrder = SortOrder.ADDED,
)

data class DownloadsUiState(
    val loading: Boolean = true,
    val items: List<TorrentItem> = emptyList(),
    val counts: Map<StatusFilter, Int> = emptyMap(),
    val filters: ListFilters = ListFilters(),
    val selection: Set<String> = emptySet(),
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val totalCount: Int = 0,
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
}

/** Pure filtering and sorting, kept separate for unit tests. */
object ListLogic {
    fun matches(item: TorrentItem, filter: StatusFilter): Boolean = when (filter) {
        StatusFilter.ALL -> true
        StatusFilter.ACTIVE -> item.state in setOf(
            TorrentState.DOWNLOADING, TorrentState.METADATA, TorrentState.CHECKING, TorrentState.MOVING, TorrentState.QUEUED,
        )
        StatusFilter.PAUSED -> item.state == TorrentState.PAUSED || item.state == TorrentState.ERROR
        StatusFilter.COMPLETED -> item.state == TorrentState.COMPLETED
    }

    fun apply(items: List<TorrentItem>, f: ListFilters): List<TorrentItem> {
        val q = f.query.trim().lowercase()
        val filtered = items.filter { item ->
            matches(item, f.status) &&
                (q.isEmpty() || item.name.lowercase().contains(q) || item.tags.any { it.lowercase().contains(q) } ||
                    item.category?.lowercase()?.contains(q) == true) &&
                (f.category == null || item.category == f.category) &&
                (f.tag == null || f.tag in item.tags)
        }
        return when (f.sort) {
            SortOrder.ADDED -> filtered.sortedByDescending { it.addedAt }
            SortOrder.NAME -> filtered.sortedBy { it.name.lowercase() }
            SortOrder.PROGRESS -> filtered.sortedByDescending { it.progress }
            SortOrder.SPEED -> filtered.sortedByDescending { it.downloadRate }
            SortOrder.SIZE -> filtered.sortedByDescending { it.wantedBytes }
            SortOrder.QUEUE -> filtered.sortedWith(compareBy<TorrentItem> { if (it.queuePosition < 0) Int.MAX_VALUE else it.queuePosition }.thenByDescending { it.addedAt })
        }
    }

    fun counts(items: List<TorrentItem>): Map<StatusFilter, Int> =
        StatusFilter.entries.associateWith { f -> items.count { matches(it, f) } }
}

class DownloadsViewModel(private val env: AppEnvironment) : ViewModel() {
    private val filters = MutableStateFlow(ListFilters())
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val loaded = MutableStateFlow(false)
    private val _errors = MutableSharedFlow<TorrentError>(extraBufferCapacity = 8)
    val errors: SharedFlow<TorrentError> = _errors.asSharedFlow()

    val session: StateFlow<SessionSnapshot> = env.controller.session
    val speedHistory = env.controller.speedHistory

    val state: StateFlow<DownloadsUiState> = combine(
        env.controller.torrents,
        filters,
        selection,
        combine(env.meta.categories, env.meta.allTags) { c, t -> c.map { it.name } to t },
        combine(loaded, env.controller.session) { l, s -> l || s.running },
    ) { items, f, sel, (cats, tags), ready ->
        val visible = ListLogic.apply(items, f)
        DownloadsUiState(
            loading = !ready && items.isEmpty(),
            items = visible,
            counts = ListLogic.counts(items),
            filters = f,
            selection = sel.filterTo(LinkedHashSet()) { id -> items.any { it.id == id } },
            categories = (cats + items.mapNotNull { it.category }).distinct().sorted(),
            tags = tags,
            totalCount = items.size,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadsUiState())

    init {
        viewModelScope.launch(env.errorHandler) {
            kotlinx.coroutines.delay(1500)
            loaded.value = true
        }
    }

    fun setStatusFilter(f: StatusFilter) = filters.update { it.copy(status = f) }
    fun setQuery(q: String) = filters.update { it.copy(query = q) }
    fun setCategory(c: String?) = filters.update { it.copy(category = c) }
    fun setTag(t: String?) = filters.update { it.copy(tag = t) }
    fun setSort(s: SortOrder) = filters.update { it.copy(sort = s) }

    fun toggleSelection(id: String) = selection.update { if (id in it) it - id else it + id }
    fun selectAll() = selection.update { state.value.items.map { it.id }.toSet() }
    fun clearSelection() = selection.update { emptySet() }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch(env.errorHandler) {
            try {
                block()
            } catch (e: GraphiteException) {
                _errors.tryEmit(e.error)
            } catch (e: Exception) {
                _errors.tryEmit(ErrorMapper.fromException(e))
            }
        }
    }

    fun pause(ids: Collection<String>) = launchAction { env.controller.pause(ids) }
    fun resume(ids: Collection<String>) = launchAction { env.controller.resume(ids) }

    fun togglePause(item: TorrentItem) = when (item.state) {
        TorrentState.PAUSED, TorrentState.ERROR -> resume(listOf(item.id))
        TorrentState.COMPLETED -> Unit
        else -> pause(listOf(item.id))
    }

    fun remove(ids: Collection<String>, deleteFiles: Boolean) = launchAction {
        env.controller.remove(ids, deleteFiles)
        selection.update { it - ids.toSet() }
    }

    fun recheck(id: String) = launchAction { env.controller.recheck(id) }
    fun setCategory(ids: Collection<String>, category: String?) = launchAction { env.controller.setCategory(ids, category) }
    fun pauseAll() = launchAction { env.controller.pauseAll() }
    fun resumeAll() = launchAction { env.controller.resumeAll() }

    /** Moves [id] to the queue position of [targetId] (drag and drop in "Queue" order). */
    fun reorder(id: String, targetId: String) = launchAction {
        val target = env.controller.torrents.value.firstOrNull { it.id == targetId } ?: return@launchAction
        if (target.queuePosition >= 0) env.controller.setQueuePosition(id, target.queuePosition)
    }

    fun moveInQueue(id: String, delta: Int) = launchAction {
        val item = env.controller.torrents.value.firstOrNull { it.id == id } ?: return@launchAction
        if (item.queuePosition >= 0) env.controller.setQueuePosition(id, (item.queuePosition + delta).coerceAtLeast(0))
    }
}
