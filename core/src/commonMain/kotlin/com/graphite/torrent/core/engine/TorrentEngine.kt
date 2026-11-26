package com.graphite.torrent.core.engine

import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.model.PeerEntry
import com.graphite.torrent.core.model.PieceMapSnapshot
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentMetadata
import com.graphite.torrent.core.model.TrackerEntry
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Events emitted by the engine; the data layer turns them into the event log, history and notifications. */
sealed interface EngineEvent {
    data class Added(val id: String, val name: String) : EngineEvent
    data class Restored(val id: String) : EngineEvent
    data class MetadataReceived(val id: String, val name: String) : EngineEvent

    /** A download reached 100 %. [fresh] is false when the torrent was already complete before this session. */
    data class Finished(val id: String, val name: String, val fresh: Boolean) : EngineEvent
    data class Failed(val id: String, val name: String, val error: TorrentError) : EngineEvent
    data class Removed(val id: String) : EngineEvent
    data class FilesDeleted(val id: String) : EngineEvent
    data class Moved(val id: String, val path: String) : EngineEvent
    data class MoveFailed(val id: String, val error: TorrentError) : EngineEvent
    data class Checked(val id: String) : EngineEvent
    data class ResumeRejected(val id: String, val error: TorrentError) : EngineEvent
    data class ListenSucceeded(val port: Int) : EngineEvent
    data class ListenFailed(val port: Int, val error: TorrentError) : EngineEvent

    /** The engine switched to another port because the configured one was busy. */
    data class PortChanged(val port: Int) : EngineEvent
    data class PortMapped(val externalPort: Int) : EngineEvent
}

/** Abstraction over the BitTorrent engine so the data and UI layers can be tested with fakes. */
interface TorrentEngine {
    val torrents: StateFlow<Map<String, TorrentItem>>
    val session: StateFlow<SessionSnapshot>
    val events: SharedFlow<EngineEvent>

    suspend fun start(config: EngineConfig)
    suspend fun stop()
    fun updateConfig(config: EngineConfig)
    fun setPauseReasons(reasons: Set<PauseReason>)

    /** Downloads metadata for a magnet link without starting the download. */
    suspend fun fetchMetadata(magnetUri: String, timeoutSeconds: Int): TorrentMetadata

    /** Parses a .torrent file. Throws [com.graphite.torrent.core.model.GraphiteException] when invalid. */
    fun parseTorrentFile(data: ByteArray): TorrentMetadata

    /** Adds a torrent and returns its id (hex info hash). */
    suspend fun add(request: AddRequest, defaultSavePath: String, extraTrackers: List<String>): String

    fun pause(id: String)
    fun resume(id: String)
    fun remove(id: String, deleteFiles: Boolean)
    fun recheck(id: String)
    fun move(id: String, targetDir: String)
    fun setFilePriorities(id: String, priorities: Map<Int, FilePriority>)
    fun setSequential(id: String, enabled: Boolean)
    fun setQueuePosition(id: String, position: Int)
    fun addTrackers(id: String, urls: List<String>)
    fun removeTrackers(id: String, urls: List<String>)
    fun reannounce(id: String)

    fun files(id: String): List<FileEntry>
    fun peers(id: String): List<PeerEntry>
    fun trackers(id: String): List<TrackerEntry>
    fun pieces(id: String): PieceMapSnapshot
    fun metadata(id: String): TorrentMetadata?

    /** Absolute path of a file on disk, or null when unknown. */
    fun filePath(id: String, fileIndex: Int): String?

    /** Piece span [first, last] covering a byte range of one file, or null without metadata. */
    fun pieceSpan(id: String, fileIndex: Int, offset: Long, length: Long): IntRange?
    fun havePiece(id: String, piece: Int): Boolean

    /** Asks libtorrent to fetch these pieces first (streaming). */
    fun prioritizePieces(id: String, pieces: IntRange, deadlineMs: Int)

    /** Re-adds a torrent from exported resume data (backup import). Returns its id. */
    suspend fun addFromResumeData(data: ByteArray): String

    /** Current resume data of a torrent, for backups; null when unknown. */
    fun resumeData(id: String): ByteArray?

    fun setIpFilter(ranges: List<IpRange>)
    suspend fun saveAllResumeData()
}
