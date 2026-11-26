package com.graphite.torrent.testing

import com.graphite.torrent.core.engine.EngineConfig
import com.graphite.torrent.core.engine.EngineEvent
import com.graphite.torrent.core.engine.IpRange
import com.graphite.torrent.core.engine.TorrentEngine
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.AddSource
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.MetaFile
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.model.PeerEntry
import com.graphite.torrent.core.model.PieceMapSnapshot
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentMetadata
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.core.model.TrackerEntry
import com.graphite.torrent.core.util.MagnetLink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory [TorrentEngine] for data and UI tests: no network, no native library. */
class FakeTorrentEngine : TorrentEngine {
    val state = MutableStateFlow<Map<String, TorrentItem>>(emptyMap())
    val sessionState = MutableStateFlow(SessionSnapshot(running = true))
    val eventFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    var lastConfig: EngineConfig? = null
    var appliedPauseReasons: Set<PauseReason> = emptySet()
    val moves = ArrayList<Pair<String, String>>()
    val ipFilters = ArrayList<List<IpRange>>()
    var lastExtraTrackers: List<String> = emptyList()
    var lastSavePath: String = ""

    override val torrents: StateFlow<Map<String, TorrentItem>> = state
    override val session: StateFlow<SessionSnapshot> = sessionState
    override val events: SharedFlow<EngineEvent> = eventFlow

    override suspend fun start(config: EngineConfig) { lastConfig = config }
    override suspend fun stop() {}
    override fun updateConfig(config: EngineConfig) { lastConfig = config }
    override fun setPauseReasons(reasons: Set<PauseReason>) { appliedPauseReasons = reasons }

    override suspend fun fetchMetadata(magnetUri: String, timeoutSeconds: Int): TorrentMetadata =
        metadata("meta", MagnetLink.parse(magnetUri)?.id ?: throw GraphiteException(ErrorKind.INVALID_TORRENT))

    override fun parseTorrentFile(data: ByteArray): TorrentMetadata {
        if (data.isEmpty()) throw GraphiteException(ErrorKind.INVALID_TORRENT, "empty")
        return metadata("file", "f".repeat(40))
    }

    fun metadata(name: String, id: String) = TorrentMetadata(
        id = id, name = name, totalSize = 1000, pieceLength = 100, pieceCount = 10, isPrivate = false,
        comment = "", createdBy = "", creationDate = 0, trackers = emptyList(),
        files = listOf(MetaFile(0, "$name/a.bin", 1000)), torrentDataBase64 = "",
    )

    override suspend fun add(request: AddRequest, defaultSavePath: String, extraTrackers: List<String>): String {
        lastExtraTrackers = extraTrackers
        lastSavePath = defaultSavePath
        val (id, name) = when (val src = request.source) {
            is AddSource.Magnet -> (MagnetLink.parse(src.uri) ?: throw GraphiteException(ErrorKind.INVALID_TORRENT)).let { it.id to (it.displayName ?: it.id) }
            is AddSource.Preview -> src.metadata.id to src.metadata.name
            is AddSource.TorrentFile -> {
                if (com.graphite.torrent.core.util.Base64Util.decode(src.dataBase64).isEmpty()) throw GraphiteException(ErrorKind.INVALID_TORRENT, "empty")
                "f".repeat(40) to "file"
            }
        }
        if (id in state.value) throw GraphiteException(ErrorKind.DUPLICATE, name)
        state.update { it + (id to item(id, name, defaultSavePath)) }
        eventFlow.tryEmit(EngineEvent.Added(id, name))
        return id
    }

    fun item(id: String, name: String, path: String, state: TorrentState = TorrentState.DOWNLOADING) = TorrentItem(
        id = id, name = name, state = state, progress = 0.5f, wantedBytes = 1000, doneBytes = 500, totalBytes = 1000,
        downloadRate = 100, uploadRate = 0, uploadedPayload = 0, downloadedPayload = 500, etaSeconds = 5,
        connectedPeers = 1, connectedSeeds = 1, totalPeers = 2, totalSeeds = 2, savePath = path, queuePosition = 0,
        sequential = false, hasMetadata = true, isPrivate = false, addedAt = 1, completedAt = 0,
    )

    fun complete(id: String) {
        state.update { m -> m + (id to m.getValue(id).copy(state = TorrentState.COMPLETED, progress = 1f, doneBytes = 1000)) }
        eventFlow.tryEmit(EngineEvent.Finished(id, state.value.getValue(id).name, fresh = true))
    }

    private fun setState(id: String, s: TorrentState) = state.update { m -> m[id]?.let { m + (id to it.copy(state = s)) } ?: m }

    override fun pause(id: String) = setState(id, TorrentState.PAUSED)
    override fun resume(id: String) = setState(id, TorrentState.DOWNLOADING)
    override fun remove(id: String, deleteFiles: Boolean) { state.update { it - id } }
    override fun recheck(id: String) = setState(id, TorrentState.CHECKING)
    override fun move(id: String, targetDir: String) { moves += id to targetDir }
    override fun setFilePriorities(id: String, priorities: Map<Int, FilePriority>) {}
    override fun setSequential(id: String, enabled: Boolean) {}
    override fun setQueuePosition(id: String, position: Int) {}
    override fun addTrackers(id: String, urls: List<String>) {}
    override fun removeTrackers(id: String, urls: List<String>) {}
    override fun reannounce(id: String) {}
    override fun files(id: String): List<FileEntry> = listOf(FileEntry(0, "a.bin", 1000, 1000, FilePriority.NORMAL))
    override fun peers(id: String): List<PeerEntry> = emptyList()
    override fun trackers(id: String): List<TrackerEntry> = emptyList()
    override fun pieces(id: String): PieceMapSnapshot = PieceMapSnapshot.EMPTY
    override fun metadata(id: String): TorrentMetadata? = null
    override fun filePath(id: String, fileIndex: Int): String? = null
    override fun pieceSpan(id: String, fileIndex: Int, offset: Long, length: Long): IntRange? = null
    override fun havePiece(id: String, piece: Int): Boolean = false
    override fun prioritizePieces(id: String, pieces: IntRange, deadlineMs: Int) {}
    override fun setIpFilter(ranges: List<IpRange>) { ipFilters += ranges }
    val resume = HashMap<String, ByteArray>()
    override suspend fun addFromResumeData(data: ByteArray): String {
        val id = String(data)
        state.update { it + (id to item(id, "restored-$id", "/restored")) }
        return id
    }
    override fun resumeData(id: String): ByteArray? = resume[id] ?: id.toByteArray()
    override suspend fun saveAllResumeData() {}
}
