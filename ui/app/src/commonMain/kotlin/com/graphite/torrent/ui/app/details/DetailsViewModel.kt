package com.graphite.torrent.ui.app.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphite.torrent.core.engine.ErrorMapper
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.PeerEntry
import com.graphite.torrent.core.model.PieceMapSnapshot
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.core.model.TrackerEntry
import com.graphite.torrent.ui.app.AppEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class DetailsTab { OVERVIEW, FILES, PEERS, TRACKERS, PIECES }

class DetailsViewModel(private val env: AppEnvironment, val torrentId: String) : ViewModel() {
    val item: StateFlow<TorrentItem?> = env.controller.torrents
        .map { list -> list.firstOrNull { it.id == torrentId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, env.controller.torrents.value.firstOrNull { it.id == torrentId })

    private val _tab = MutableStateFlow(DetailsTab.OVERVIEW)
    val tab: StateFlow<DetailsTab> = _tab.asStateFlow()

    private val _files = MutableStateFlow<List<FileEntry>?>(null)
    val files: StateFlow<List<FileEntry>?> = _files.asStateFlow()
    private val _peers = MutableStateFlow<List<PeerEntry>?>(null)
    val peers: StateFlow<List<PeerEntry>?> = _peers.asStateFlow()
    private val _trackers = MutableStateFlow<List<TrackerEntry>?>(null)
    val trackers: StateFlow<List<TrackerEntry>?> = _trackers.asStateFlow()
    private val _pieces = MutableStateFlow<PieceMapSnapshot?>(null)
    val pieces: StateFlow<PieceMapSnapshot?> = _pieces.asStateFlow()

    private val _errors = MutableSharedFlow<TorrentError>(extraBufferCapacity = 8)
    val errors: SharedFlow<TorrentError> = _errors.asSharedFlow()

    val categories = env.meta.categories
    val streamingAvailable: Boolean = env.features.streaming != null

    init {
        // Files are needed by the overview too (playable files), load them once immediately.
        viewModelScope.launch(env.errorHandler) { refresh(DetailsTab.FILES) }
        // The open tab is refreshed only while the screen is really shown: the UI stops collecting [tab]
        // when the app goes to the background, and the engine is left alone until it returns.
        viewModelScope.launch(env.errorHandler) {
            _tab.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { shown ->
                if (shown) {
                    _tab.collectLatest { tab ->
                        while (isActive) {
                            refresh(tab)
                            delay(POLL_MS)
                        }
                    }
                }
            }
        }
    }

    fun selectTab(tab: DetailsTab) {
        _tab.value = tab
    }

    private suspend fun refresh(tab: DetailsTab) {
        val live = item.value?.let { it.finalLocation == null } ?: true
        if (!live) return
        // Unchanged lists are dropped by the StateFlow itself (equal value), so nothing is redrawn.
        runCatching {
            when (tab) {
                DetailsTab.OVERVIEW -> Unit
                DetailsTab.FILES -> _files.value = env.controller.files(torrentId)
                DetailsTab.PEERS -> _peers.value = env.controller.peers(torrentId)
                DetailsTab.TRACKERS -> _trackers.value = env.controller.trackers(torrentId)
                DetailsTab.PIECES -> _pieces.value = env.controller.pieces(torrentId)
            }
        }
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch(env.errorHandler) {
            try {
                block()
                refresh(_tab.value)
            } catch (e: GraphiteException) {
                _errors.tryEmit(e.error)
            } catch (e: Exception) {
                _errors.tryEmit(ErrorMapper.fromException(e))
            }
        }
    }

    fun togglePause() {
        val current = item.value ?: return
        action {
            when (current.state) {
                TorrentState.PAUSED, TorrentState.ERROR -> env.controller.resume(listOf(torrentId))
                TorrentState.COMPLETED -> Unit
                else -> env.controller.pause(listOf(torrentId))
            }
        }
    }

    fun remove(deleteFiles: Boolean, onDone: () -> Unit) = action {
        env.controller.remove(listOf(torrentId), deleteFiles)
        onDone()
    }

    fun recheck() = action { env.controller.recheck(torrentId) }
    fun reannounce() = action { env.controller.reannounce(torrentId) }
    fun setSequential(enabled: Boolean) = action { env.controller.setSequential(torrentId, enabled) }
    fun setPriorities(priorities: Map<Int, FilePriority>) = action {
        // Optimistic update so the checkbox reacts immediately.
        _files.value = _files.value?.map { f -> priorities[f.index]?.let { f.copy(priority = it) } ?: f }
        env.controller.setFilePriorities(torrentId, priorities)
    }

    fun addTracker(url: String) = action { env.controller.addTrackers(torrentId, listOf(url.trim())) }
    fun removeTracker(url: String) = action { env.controller.removeTrackers(torrentId, listOf(url)) }
    fun setCategory(category: String?) = action { env.controller.setCategory(listOf(torrentId), category) }
    fun setTags(tags: List<String>) = action { env.controller.setTags(torrentId, tags) }

    fun moveTo() = action {
        val folder = env.platform.pickFolder() ?: return@action
        env.controller.move(torrentId, folder)
    }

    fun copyMagnet() {
        item.value?.magnetUri?.takeIf { it.isNotBlank() }?.let(env.platform::copyToClipboard)
    }

    fun openFolder() {
        val i = item.value ?: return
        env.platform.openFolder(i.finalLocation ?: i.savePath)
    }

    fun exportTorrent() = action {
        val bytes = env.local.exportTorrent(torrentId) ?: return@action
        val name = (item.value?.name ?: torrentId).replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".torrent"
        env.platform.saveFile(name, "application/x-bittorrent", bytes)
    }

    companion object {
        const val POLL_MS = 1500L
    }
}
