package com.graphite.torrent.core.model

import kotlinx.serialization.Serializable

/** High level lifecycle state of a torrent as shown to the user. */
@Serializable
enum class TorrentState {
    /** Verifying data on disk (hash check or resume data check). */
    CHECKING,

    /** Magnet link: waiting for metadata from peers or DHT. */
    METADATA,

    /** Actively downloading. */
    DOWNLOADING,

    /** Waiting in the auto-managed queue for a free download slot. */
    QUEUED,

    /** Paused by the user (or by a session-wide pause reason). */
    PAUSED,

    /** All wanted data has been downloaded. */
    COMPLETED,

    /** Files are being moved to another folder. */
    MOVING,

    /** The torrent stopped because of an error, see [TorrentItem.error]. */
    ERROR,
}

/** Categories of failures that the UI turns into a human readable message with advice. */
@Serializable
enum class ErrorKind {
    NO_SPACE,
    NO_NETWORK,
    INVALID_TORRENT,
    FOLDER_UNAVAILABLE,
    PERMISSION_DENIED,
    FILE_IO,
    METADATA_TIMEOUT,
    PORT_IN_USE,
    DUPLICATE,
    TRACKER,
    REMOTE_UNREACHABLE,
    REMOTE_AUTH,
    UNKNOWN,
}

@Serializable
data class TorrentError(
    val kind: ErrorKind,
    /** Technical detail (libtorrent message, path, exception text). Shown in the event log. */
    val detail: String = "",
)

/** Thrown by controller operations so callers get a classified error instead of a raw exception. */
class GraphiteException(val error: TorrentError, cause: Throwable? = null) :
    Exception("${error.kind}: ${error.detail}", cause) {
    constructor(kind: ErrorKind, detail: String = "", cause: Throwable? = null) :
        this(TorrentError(kind, detail), cause)
}

/** One torrent as displayed in lists: engine status merged with user metadata (category, tags). */
@Serializable
data class TorrentItem(
    val id: String,
    val name: String,
    val state: TorrentState,
    /** 0..1 relative to the wanted (selected) files. */
    val progress: Float,
    /** Bytes of the selected files. */
    val wantedBytes: Long,
    /** Downloaded and verified bytes of the selected files. */
    val doneBytes: Long,
    /** Total size of all files in the torrent (0 while metadata is missing). */
    val totalBytes: Long,
    /** Payload download speed, bytes per second. */
    val downloadRate: Long,
    /** Payload upload speed, bytes per second (expected to be 0 in zero-upload mode). */
    val uploadRate: Long,
    /** Payload bytes uploaded during the whole lifetime of this torrent. */
    val uploadedPayload: Long,
    /** Payload bytes downloaded during the whole lifetime of this torrent. */
    val downloadedPayload: Long,
    /** Estimated seconds to completion, or -1 when unknown. */
    val etaSeconds: Long,
    val connectedPeers: Int,
    val connectedSeeds: Int,
    val totalPeers: Int,
    val totalSeeds: Int,
    val savePath: String,
    /** Position in the download queue, -1 when not queued. */
    val queuePosition: Int,
    val sequential: Boolean,
    val hasMetadata: Boolean,
    val isPrivate: Boolean,
    val addedAt: Long,
    /** Epoch millis when the download completed, 0 when not yet completed. */
    val completedAt: Long,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val error: TorrentError? = null,
    /** Final location after a post-completion move (e.g. SAF folder on Android), null when not moved. */
    val finalLocation: String? = null,
    val magnetUri: String = "",
)

@Serializable
enum class FilePriority { SKIP, LOW, NORMAL, HIGH }

@Serializable
data class FileEntry(
    val index: Int,
    /** Path inside the torrent, using '/' as separator. */
    val path: String,
    val size: Long,
    val downloaded: Long,
    val priority: FilePriority,
) {
    val name: String get() = path.substringAfterLast('/')
    val progress: Float get() = if (size <= 0) 1f else (downloaded.toDouble() / size).toFloat().coerceIn(0f, 1f)
}

@Serializable
enum class PeerSource { TRACKER, DHT, PEX, LSD, RESUME, INCOMING, WEB_SEED }

@Serializable
data class PeerEntry(
    val address: String,
    val client: String,
    val downloadRate: Long,
    val uploadRate: Long,
    val progress: Float,
    val downloaded: Long,
    val uploaded: Long,
    val sources: Set<PeerSource>,
    val isSeed: Boolean,
    val isUtp: Boolean,
    val isEncrypted: Boolean,
    val isIncoming: Boolean,
)

@Serializable
enum class TrackerStatus { WORKING, UPDATING, ERROR, NOT_CONTACTED, DISABLED }

@Serializable
data class TrackerEntry(
    val url: String,
    val tier: Int,
    val status: TrackerStatus,
    val message: String,
    val seeds: Int,
    val peers: Int,
    val downloaded: Int,
    /** True for the pseudo entries DHT / PEX / LSD that are not real trackers. */
    val isPseudo: Boolean = false,
)

/** Piece states for the piece map, one byte per piece. */
object PieceState {
    const val MISSING: Byte = 0
    const val QUEUED: Byte = 1
    const val DOWNLOADING: Byte = 2
    const val DONE: Byte = 3
}

@Serializable
data class PieceMapSnapshot(
    val pieceCount: Int,
    val pieceSize: Int,
    /** Base64 is avoided for simplicity; states are small integers 0..3 per piece. */
    val states: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PieceMapSnapshot && other.pieceCount == pieceCount && other.pieceSize == pieceSize &&
            other.states.contentEquals(states)

    override fun hashCode(): Int = 31 * (31 * pieceCount + pieceSize) + states.contentHashCode()

    companion object {
        val EMPTY = PieceMapSnapshot(0, 0, ByteArray(0))
    }
}

@Serializable
enum class PortStatus {
    /** Session is starting or the port has not been checked yet. */
    CHECKING,

    /** Incoming connections were observed: the port is reachable from the internet. */
    OPEN,

    /** UPnP/NAT-PMP mapped the port; reachability is very likely. */
    MAPPED,

    /** Listening, but no mapping and no incoming connections so far. */
    FIREWALLED,

    /** Could not listen on any port. */
    FAILED,
}

/** Why the whole session is paused (independently of per-torrent pauses). */
@Serializable
enum class PauseReason { USER, SCHEDULE, NO_WIFI, NOT_CHARGING, LOW_BATTERY, KILL_SWITCH, NO_NETWORK }

@Serializable
data class SessionSnapshot(
    val downloadRate: Long = 0,
    val uploadRate: Long = 0,
    /** Payload bytes downloaded since the session started. */
    val sessionDownloaded: Long = 0,
    /** Payload bytes uploaded since the session started (0 in zero-upload mode). */
    val sessionUploaded: Long = 0,
    /** Payload bytes uploaded by all torrents during their whole lifetime. */
    val allTimeUploaded: Long = 0,
    val activeTorrents: Int = 0,
    val totalTorrents: Int = 0,
    val dhtNodes: Long = 0,
    val listenPort: Int = 0,
    val portStatus: PortStatus = PortStatus.CHECKING,
    val externalAddress: String = "",
    val pauseReasons: Set<PauseReason> = emptySet(),
    val zeroUpload: Boolean = true,
    val running: Boolean = false,
    val libtorrentVersion: String = "",
)

@Serializable
data class SpeedSample(
    val timestamp: Long,
    val downloadRate: Long,
    val uploadRate: Long,
)

@Serializable
data class MetaFile(
    val index: Int,
    val path: String,
    val size: Long,
)

/** Torrent metadata used for the "preview before download" step. */
@Serializable
data class TorrentMetadata(
    val id: String,
    val name: String,
    val totalSize: Long,
    val pieceLength: Int,
    val pieceCount: Int,
    val isPrivate: Boolean,
    val comment: String,
    val createdBy: String,
    val creationDate: Long,
    val trackers: List<String>,
    val files: List<MetaFile>,
    /** The bencoded .torrent file, base64 encoded so it can travel over the remote protocol. */
    val torrentDataBase64: String,
    /** Magnet URI the metadata came from (empty for .torrent files). */
    val magnetUri: String = "",
)

@Serializable
sealed interface AddSource {
    @Serializable
    data class Magnet(val uri: String) : AddSource

    @Serializable
    data class TorrentFile(val dataBase64: String) : AddSource

    /** Metadata that was fetched for preview (keeps the magnet trackers). */
    @Serializable
    data class Preview(val metadata: TorrentMetadata) : AddSource
}

@Serializable
data class AddRequest(
    val source: AddSource,
    /** Save folder; null means the default download folder. */
    val savePath: String? = null,
    /** Priorities per file index; null or empty means everything NORMAL. */
    val filePriorities: Map<Int, FilePriority>? = null,
    val sequential: Boolean? = null,
    val startPaused: Boolean = false,
    val category: String? = null,
    val tags: List<String> = emptyList(),
)
