package com.graphite.torrent.data.remote

import kotlinx.coroutines.CancellationException
import com.graphite.torrent.core.net.BoundedIo
import com.graphite.torrent.core.engine.ErrorMapper
import com.graphite.torrent.core.controller.ControllerNotice
import com.graphite.torrent.core.controller.TorrentController
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.model.PeerEntry
import com.graphite.torrent.core.model.PieceMapSnapshot
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.SpeedSample
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentMetadata
import com.graphite.torrent.core.model.TrackerEntry
import com.graphite.torrent.core.settings.RemotePairing
import com.graphite.torrent.core.util.Base64Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Android side of remote control: the same [TorrentController] API, executed on the paired PC. */
class RemoteTorrentController(
    private val pairing: RemotePairing,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : TorrentController {
    private val crypto = RemoteCrypto(Base64Util.decodeUrl(pairing.keyBase64))
    @Volatile private var host: String = pairing.hosts.first()
    private var job: Job? = null
    private var noticeSeq = -1L

    private val _torrents = MutableStateFlow<List<TorrentItem>>(emptyList())
    private val _session = MutableStateFlow(SessionSnapshot())
    private val _history = MutableStateFlow<List<SpeedSample>>(emptyList())
    private val _notices = MutableSharedFlow<ControllerNotice>(extraBufferCapacity = 32)
    private val _connected = MutableStateFlow(false)
    private val _lastError = MutableStateFlow<TorrentError?>(null)

    override val torrents: StateFlow<List<TorrentItem>> = _torrents.asStateFlow()
    override val session: StateFlow<SessionSnapshot> = _session.asStateFlow()
    override val speedHistory: StateFlow<List<SpeedSample>> = _history.asStateFlow()
    override val notices: SharedFlow<ControllerNotice> = _notices.asSharedFlow()
    override val isRemote: Boolean = true
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    val lastError: StateFlow<TorrentError?> = _lastError.asStateFlow()
    val name: String get() = pairing.name

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try {
                    val snap = call("snapshot", SinceParam(noticeSeq).json(SinceParam.serializer()), RemoteSnapshot.serializer())
                    _torrents.value = snap.torrents
                    _session.value = snap.session
                    _history.value = snap.history
                    if (noticeSeq >= 0) snap.notices.forEach { _notices.tryEmit(it) }
                    noticeSeq = snap.noticeSeq
                    _connected.value = true
                    _lastError.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Whatever answers at that address (another network, a newer or older PC version) must not
                    // stop the polling: show "not reachable" and keep trying.
                    _connected.value = false
                    _lastError.value = (e as? GraphiteException)?.error ?: ErrorMapper.fromException(e)
                    _session.value = _session.value.copy(running = false, downloadRate = 0, uploadRate = 0)
                }
                delay(if (_connected.value) 1000 else 3000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One-off check used right after pairing. */
    suspend fun ping(): Boolean = runCatching {
        call("snapshot", SinceParam(Long.MAX_VALUE).json(SinceParam.serializer()), RemoteSnapshot.serializer())
    }.isSuccess

    private fun <T> T.json(serializer: KSerializer<T>): JsonElement = RemoteJson.encodeToJsonElement(serializer, this)

    private suspend fun <T> call(method: String, params: JsonElement, serializer: KSerializer<T>, timeoutMs: Int = 15_000): T {
        val result = rawCall(method, params, timeoutMs)
        return RemoteJson.decodeFromJsonElement(serializer, result)
    }

    private suspend fun rawCall(method: String, params: JsonElement, timeoutMs: Int): JsonElement = withContext(Dispatchers.IO) {
        val request = RpcRequest(clock(), UUID.randomUUID().toString(), method, params)
        val body = RemoteJson.encodeToString(
            Envelope.serializer(),
            crypto.seal(RemoteJson.encodeToString(RpcRequest.serializer(), request).toByteArray(), RemoteCrypto.REQUEST_AAD),
        )
        var lastFailure: Exception? = null
        for (candidate in listOf(host) + pairing.hosts.filter { it != host }) {
            try {
                val text = post(candidate, body, timeoutMs)
                host = candidate
                val envelope = RemoteJson.decodeFromString(Envelope.serializer(), text)
                val response = RemoteJson.decodeFromString(
                    RpcResponse.serializer(),
                    crypto.open(envelope, RemoteCrypto.responseAad(request.id)).toString(Charsets.UTF_8),
                )
                if (!response.ok) throw GraphiteException(response.error ?: TorrentError(ErrorKind.UNKNOWN))
                return@withContext response.result
            } catch (e: GraphiteException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw GraphiteException(ErrorKind.REMOTE_UNREACHABLE, "${pairing.name}: ${lastFailure?.message.orEmpty()}", lastFailure)
    }

    private fun post(host: String, body: String, timeoutMs: Int): String {
        val authority = if (host.contains(':')) "[$host]" else host
        val connection = URI("http://$authority:${pairing.port}/rpc").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4000
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            // Only the paired PC is spoken to: a redirect elsewhere is an error, not something to follow.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code == 403) throw GraphiteException(ErrorKind.REMOTE_AUTH, "rejected by ${pairing.name}")
            if (code != 200) throw IOException("HTTP $code")
            return connection.inputStream.use { BoundedIo.read(it, MAX_RESPONSE_BYTES, System.currentTimeMillis() + timeoutMs * 2L) }
                .toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun unit(method: String, params: JsonElement) {
        rawCall(method, params, 30_000)
    }

    override suspend fun previewMagnet(uri: String): TorrentMetadata =
        call("previewMagnet", UriParam(uri).json(UriParam.serializer()), TorrentMetadata.serializer(), timeoutMs = 120_000)

    override suspend fun previewTorrentFile(data: ByteArray): TorrentMetadata =
        call("previewTorrentFile", DataParam(Base64Util.encode(data)).json(DataParam.serializer()), TorrentMetadata.serializer())

    override suspend fun add(request: AddRequest): String =
        call("add", request.json(AddRequest.serializer()), String.serializer(), timeoutMs = 60_000)

    override suspend fun pause(ids: Collection<String>) = unit("pause", IdsParam(ids.toList()).json(IdsParam.serializer()))
    override suspend fun resume(ids: Collection<String>) = unit("resume", IdsParam(ids.toList()).json(IdsParam.serializer()))
    override suspend fun remove(ids: Collection<String>, deleteFiles: Boolean) = unit("remove", IdsParam(ids.toList(), deleteFiles).json(IdsParam.serializer()))
    override suspend fun recheck(id: String) = unit("recheck", IdParam(id).json(IdParam.serializer()))
    override suspend fun move(id: String, targetDir: String) = unit("move", MoveParam(id, targetDir).json(MoveParam.serializer()))
    override suspend fun setFilePriorities(id: String, priorities: Map<Int, FilePriority>) =
        unit("setFilePriorities", PrioritiesParam(id, priorities).json(PrioritiesParam.serializer()))
    override suspend fun setSequential(id: String, enabled: Boolean) = unit("setSequential", FlagParam(id, enabled).json(FlagParam.serializer()))
    override suspend fun setQueuePosition(id: String, position: Int) = unit("setQueuePosition", PositionParam(id, position).json(PositionParam.serializer()))
    override suspend fun setCategory(ids: Collection<String>, category: String?) = unit("setCategory", CategoryParam(ids.toList(), category).json(CategoryParam.serializer()))
    override suspend fun setTags(id: String, tags: List<String>) = unit("setTags", TagsParam(id, tags).json(TagsParam.serializer()))
    override suspend fun addTrackers(id: String, urls: List<String>) = unit("addTrackers", UrlsParam(id, urls).json(UrlsParam.serializer()))
    override suspend fun removeTrackers(id: String, urls: List<String>) = unit("removeTrackers", UrlsParam(id, urls).json(UrlsParam.serializer()))
    override suspend fun reannounce(id: String) = unit("reannounce", IdParam(id).json(IdParam.serializer()))
    override suspend fun files(id: String): List<FileEntry> = call("files", IdParam(id).json(IdParam.serializer()), ListSerializer(FileEntry.serializer()))
    override suspend fun peers(id: String): List<PeerEntry> = call("peers", IdParam(id).json(IdParam.serializer()), ListSerializer(PeerEntry.serializer()))
    override suspend fun trackers(id: String): List<TrackerEntry> = call("trackers", IdParam(id).json(IdParam.serializer()), ListSerializer(TrackerEntry.serializer()))
    override suspend fun pieces(id: String): PieceMapSnapshot = call("pieces", IdParam(id).json(IdParam.serializer()), PieceMapSnapshot.serializer())
    override suspend fun pauseAll() = unit("pauseAll", JsonNull)
    override suspend fun resumeAll() = unit("resumeAll", JsonNull)
    override suspend fun freeSpace(path: String): Long = call("freeSpace", PathParam(path).json(PathParam.serializer()), Long.serializer())
    override suspend fun defaultSavePath(): String = call("defaultSavePath", JsonNull, String.serializer())

    /** Pause rules of the phone never pause the PC. */
    override fun setPauseReason(reason: PauseReason, active: Boolean) = Unit

    private companion object {
        /** A snapshot of several hundred downloads is a few megabytes at most. */
        const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
    }
}
