package com.graphite.torrent.core.controller

import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.model.PeerEntry
import com.graphite.torrent.core.model.PieceMapSnapshot
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.SpeedSample
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentMetadata
import com.graphite.torrent.core.model.TrackerEntry
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Short notices for snackbars and notifications. */
@Serializable
sealed interface ControllerNotice {
    @Serializable
    data class Added(val id: String, val name: String) : ControllerNotice

    @Serializable
    data class Completed(val id: String, val name: String) : ControllerNotice

    @Serializable
    data class Failed(val id: String?, val name: String, val error: TorrentError) : ControllerNotice

    @Serializable
    data class Moved(val id: String, val name: String, val location: String) : ControllerNotice
}

/**
 * Everything the UI can do with torrents. Implemented locally (engine + database) and remotely
 * (the Android app controlling the desktop client over the LAN), so the same screens work for both.
 *
 * All operations throw [com.graphite.torrent.core.model.GraphiteException] on failure.
 */
interface TorrentController {
    val torrents: StateFlow<List<TorrentItem>>
    val session: StateFlow<SessionSnapshot>
    val speedHistory: StateFlow<List<SpeedSample>>
    val notices: SharedFlow<ControllerNotice>
    val isRemote: Boolean

    suspend fun previewMagnet(uri: String): TorrentMetadata
    suspend fun previewTorrentFile(data: ByteArray): TorrentMetadata
    suspend fun add(request: AddRequest): String

    suspend fun pause(ids: Collection<String>)
    suspend fun resume(ids: Collection<String>)
    suspend fun remove(ids: Collection<String>, deleteFiles: Boolean)
    suspend fun recheck(id: String)
    suspend fun move(id: String, targetDir: String)
    suspend fun setFilePriorities(id: String, priorities: Map<Int, FilePriority>)
    suspend fun setSequential(id: String, enabled: Boolean)
    suspend fun setQueuePosition(id: String, position: Int)
    suspend fun setCategory(ids: Collection<String>, category: String?)
    suspend fun setTags(id: String, tags: List<String>)
    suspend fun addTrackers(id: String, urls: List<String>)
    suspend fun removeTrackers(id: String, urls: List<String>)
    suspend fun reannounce(id: String)

    suspend fun files(id: String): List<FileEntry>
    suspend fun peers(id: String): List<PeerEntry>
    suspend fun trackers(id: String): List<TrackerEntry>
    suspend fun pieces(id: String): PieceMapSnapshot

    suspend fun pauseAll()
    suspend fun resumeAll()

    /** Free bytes in the folder (or the nearest existing parent). */
    suspend fun freeSpace(path: String): Long
    suspend fun defaultSavePath(): String

    /** Session-wide pause driven by rules (Wi-Fi only, battery, schedule, kill switch). */
    fun setPauseReason(reason: PauseReason, active: Boolean)
}
