package com.graphite.torrent.data

import com.graphite.torrent.core.controller.ControllerNotice
import com.graphite.torrent.core.controller.TorrentController
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.model.PeerEntry
import com.graphite.torrent.core.model.PieceMapSnapshot
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.SpeedSample
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentMetadata
import com.graphite.torrent.core.model.TrackerEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * Delegates to the local engine or to a remote desktop. Screens hold this controller, so switching
 * to remote mode changes what they show without recreating anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableTorrentController(val local: TorrentController, scope: CoroutineScope) : TorrentController {
    private val target = MutableStateFlow(local)
    val active: StateFlow<TorrentController> = target.asStateFlow()

    fun use(controller: TorrentController) {
        target.value = controller
    }

    fun useLocal() {
        target.value = local
    }

    private val c get() = target.value

    override val torrents: StateFlow<List<TorrentItem>> =
        target.flatMapLatest { it.torrents }.stateIn(scope, SharingStarted.Eagerly, local.torrents.value)
    override val session: StateFlow<SessionSnapshot> =
        target.flatMapLatest { it.session }.stateIn(scope, SharingStarted.Eagerly, local.session.value)
    override val speedHistory: StateFlow<List<SpeedSample>> =
        target.flatMapLatest { it.speedHistory }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val notices: SharedFlow<ControllerNotice> =
        target.flatMapLatest { it.notices }.shareIn(scope, SharingStarted.Eagerly)
    override val isRemote: Boolean get() = c.isRemote

    override suspend fun previewMagnet(uri: String): TorrentMetadata = c.previewMagnet(uri)
    override suspend fun previewTorrentFile(data: ByteArray): TorrentMetadata = c.previewTorrentFile(data)
    override suspend fun add(request: AddRequest): String = c.add(request)
    override suspend fun pause(ids: Collection<String>) = c.pause(ids)
    override suspend fun resume(ids: Collection<String>) = c.resume(ids)
    override suspend fun remove(ids: Collection<String>, deleteFiles: Boolean) = c.remove(ids, deleteFiles)
    override suspend fun recheck(id: String) = c.recheck(id)
    override suspend fun move(id: String, targetDir: String) = c.move(id, targetDir)
    override suspend fun setFilePriorities(id: String, priorities: Map<Int, FilePriority>) = c.setFilePriorities(id, priorities)
    override suspend fun setSequential(id: String, enabled: Boolean) = c.setSequential(id, enabled)
    override suspend fun setQueuePosition(id: String, position: Int) = c.setQueuePosition(id, position)
    override suspend fun setCategory(ids: Collection<String>, category: String?) = c.setCategory(ids, category)
    override suspend fun setTags(id: String, tags: List<String>) = c.setTags(id, tags)
    override suspend fun addTrackers(id: String, urls: List<String>) = c.addTrackers(id, urls)
    override suspend fun removeTrackers(id: String, urls: List<String>) = c.removeTrackers(id, urls)
    override suspend fun reannounce(id: String) = c.reannounce(id)
    override suspend fun files(id: String): List<FileEntry> = c.files(id)
    override suspend fun peers(id: String): List<PeerEntry> = c.peers(id)
    override suspend fun trackers(id: String): List<TrackerEntry> = c.trackers(id)
    override suspend fun pieces(id: String): PieceMapSnapshot = c.pieces(id)
    override suspend fun pauseAll() = c.pauseAll()
    override suspend fun resumeAll() = c.resumeAll()
    override suspend fun freeSpace(path: String): Long = c.freeSpace(path)
    override suspend fun defaultSavePath(): String = c.defaultSavePath()

    /** Power and network rules always apply to the local engine. */
    override fun setPauseReason(reason: PauseReason, active: Boolean) = local.setPauseReason(reason, active)
}
