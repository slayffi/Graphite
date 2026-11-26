package com.graphite.torrent.data.remote

import com.graphite.torrent.core.controller.ControllerNotice
import com.graphite.torrent.core.controller.TorrentController
import com.graphite.torrent.core.engine.ErrorMapper
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.PieceMapSnapshot
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.net.NetworkInterfaces
import com.graphite.torrent.core.util.Base64Util
import com.graphite.torrent.data.net.HttpRequest
import com.graphite.torrent.data.net.HttpResponder
import com.graphite.torrent.data.net.MiniHttpServer
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop side of remote control: an HTTP endpoint on the LAN that executes encrypted commands
 * from the paired phone against the local [TorrentController].
 */
class RemoteServer(
    private val controller: TorrentController,
    private val settings: SettingsRepository,
    private val eventLog: EventLogRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deviceName: String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("PC"),
) {
    private var server: MiniHttpServer? = null
    private var crypto: RemoteCrypto? = null
    private val seenIds = ConcurrentHashMap<String, Long>()
    private val clients = ConcurrentHashMap<String, Long>()
    private val notices = ArrayDeque<Pair<Long, ControllerNotice>>()
    private var noticeSeq = 0L
    private var jobs = ArrayList<Job>()

    private val _pairing = MutableStateFlow<String?>(null)
    val pairingPayload: StateFlow<String?> = _pairing.asStateFlow()
    private val _clients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _clients.asStateFlow()

    /** Follows the "remote control" setting: starts, restarts on port/key change, or stops. */
    fun bind() {
        jobs += scope.launch {
            settings.settings.map { Triple(it.remoteServerEnabled, it.remotePort, it.remoteKeyBase64) }
                .distinctUntilChanged()
                .collect { (enabled, port, key) ->
                    if (!enabled) {
                        stopServer()
                    } else if (key.isBlank()) {
                        settings.update { it.copy(remoteKeyBase64 = Base64Util.encodeUrl(RemoteCrypto.newKey())) }
                    } else {
                        startServer(port, Base64Util.decodeUrl(key))
                    }
                }
        }
        jobs += scope.launch {
            controller.notices.collect { n ->
                synchronized(notices) {
                    notices.addLast(++noticeSeq to n)
                    while (notices.size > 100) notices.removeFirst()
                }
            }
        }
    }

    suspend fun regenerateKey() {
        settings.update { it.copy(remoteKeyBase64 = Base64Util.encodeUrl(RemoteCrypto.newKey())) }
    }

    private fun startServer(port: Int, key: ByteArray) {
        stopServer()
        crypto = RemoteCrypto(key)
        val s = MiniHttpServer(null, port, scope) { req, res -> handle(req, res) }
        try {
            s.start()
            server = s
            val hosts = NetworkInterfaces.list()
                .filter { it.isUp && !it.isLikelyVpn }
                .flatMap { it.addresses }
                .filter { runCatching { InetAddress.getByName(it) is Inet4Address }.getOrDefault(false) }
                .ifEmpty { listOf(InetAddress.getLocalHost().hostAddress) }
            _pairing.value = PairingCode.format(deviceName, hosts, port, key)
        } catch (e: Exception) {
            scope.launch { eventLog.error(EventType.REMOTE_CLIENT, "port $port", ErrorMapper.fromException(e)) }
            _pairing.value = null
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        _pairing.value = null
        clients.clear()
        _clients.value = 0
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        stopServer()
    }

    private suspend fun handle(req: HttpRequest, res: HttpResponder) {
        if (req.method != "POST" || req.path != "/rpc") return res.text(404, "Not Found", "")
        val c = crypto ?: return res.text(503, "Unavailable", "")
        val request = try {
            val envelope = RemoteJson.decodeFromString(Envelope.serializer(), req.body.toString(Charsets.UTF_8))
            RemoteJson.decodeFromString(RpcRequest.serializer(), c.open(envelope).toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return res.text(403, "Forbidden", "")
        }
        val now = clock()
        if (kotlin.math.abs(now - request.ts) > MAX_SKEW_MS || seenIds.putIfAbsent(request.id, now) != null) {
            return res.text(403, "Forbidden", "")
        }
        if (seenIds.size > 5000) seenIds.entries.removeIf { now - it.value > MAX_SKEW_MS * 2 }
        trackClient(req.remoteAddress, now)

        val response = try {
            RpcResponse(true, dispatch(request.method, request.params))
        } catch (e: GraphiteException) {
            RpcResponse(false, error = e.error)
        } catch (e: Exception) {
            RpcResponse(false, error = ErrorMapper.fromException(e))
        }
        val sealed = c.seal(RemoteJson.encodeToString(RpcResponse.serializer(), response).toByteArray())
        res.bytes(200, RemoteJson.encodeToString(Envelope.serializer(), sealed).toByteArray(), "application/json")
    }

    private fun trackClient(address: String, now: Long) {
        val isNew = clients.put(address, now) == null
        clients.entries.removeIf { now - it.value > 15_000 }
        _clients.value = clients.size
        if (isNew) scope.launch { eventLog.log(EventType.REMOTE_CLIENT, address) }
    }

    private inline fun <reified T> JsonElement.decode(serializer: KSerializer<T>): T = RemoteJson.decodeFromJsonElement(serializer, this)
    private fun <T> T.encode(serializer: KSerializer<T>): JsonElement = RemoteJson.encodeToJsonElement(serializer, this)

    private suspend fun dispatch(method: String, p: JsonElement): JsonElement = when (method) {
        "snapshot" -> {
            val since = p.decode(SinceParam.serializer()).since
            val pending = synchronized(notices) { notices.filter { it.first > since } }
            RemoteSnapshot(
                torrents = controller.torrents.value,
                session = controller.session.value,
                history = controller.speedHistory.value.takeLast(60),
                noticeSeq = pending.lastOrNull()?.first ?: maxOf(since, synchronized(notices) { noticeSeq }),
                notices = pending.map { it.second },
                serverName = deviceName,
            ).encode(RemoteSnapshot.serializer())
        }
        "previewMagnet" -> controller.previewMagnet(p.decode(UriParam.serializer()).uri).encode(com.graphite.torrent.core.model.TorrentMetadata.serializer())
        "previewTorrentFile" -> controller.previewTorrentFile(Base64Util.decode(p.decode(DataParam.serializer()).data)).encode(com.graphite.torrent.core.model.TorrentMetadata.serializer())
        "add" -> controller.add(p.decode(AddRequest.serializer())).encode(String.serializer())
        "pause" -> unit { controller.pause(p.decode(IdsParam.serializer()).ids) }
        "resume" -> unit { controller.resume(p.decode(IdsParam.serializer()).ids) }
        "remove" -> unit { p.decode(IdsParam.serializer()).let { controller.remove(it.ids, it.deleteFiles) } }
        "recheck" -> unit { controller.recheck(p.decode(IdParam.serializer()).id) }
        "move" -> unit { p.decode(MoveParam.serializer()).let { controller.move(it.id, it.target) } }
        "setFilePriorities" -> unit { p.decode(PrioritiesParam.serializer()).let { controller.setFilePriorities(it.id, it.priorities) } }
        "setSequential" -> unit { p.decode(FlagParam.serializer()).let { controller.setSequential(it.id, it.enabled) } }
        "setQueuePosition" -> unit { p.decode(PositionParam.serializer()).let { controller.setQueuePosition(it.id, it.position) } }
        "setCategory" -> unit { p.decode(CategoryParam.serializer()).let { controller.setCategory(it.ids, it.category) } }
        "setTags" -> unit { p.decode(TagsParam.serializer()).let { controller.setTags(it.id, it.tags) } }
        "addTrackers" -> unit { p.decode(UrlsParam.serializer()).let { controller.addTrackers(it.id, it.urls) } }
        "removeTrackers" -> unit { p.decode(UrlsParam.serializer()).let { controller.removeTrackers(it.id, it.urls) } }
        "reannounce" -> unit { controller.reannounce(p.decode(IdParam.serializer()).id) }
        "files" -> controller.files(p.decode(IdParam.serializer()).id).encode(ListSerializer(com.graphite.torrent.core.model.FileEntry.serializer()))
        "peers" -> controller.peers(p.decode(IdParam.serializer()).id).encode(ListSerializer(com.graphite.torrent.core.model.PeerEntry.serializer()))
        "trackers" -> controller.trackers(p.decode(IdParam.serializer()).id).encode(ListSerializer(com.graphite.torrent.core.model.TrackerEntry.serializer()))
        "pieces" -> controller.pieces(p.decode(IdParam.serializer()).id).encode(PieceMapSnapshot.serializer())
        "pauseAll" -> unit { controller.pauseAll() }
        "resumeAll" -> unit { controller.resumeAll() }
        "freeSpace" -> controller.freeSpace(p.decode(PathParam.serializer()).path).encode(Long.serializer())
        "defaultSavePath" -> controller.defaultSavePath().encode(String.serializer())
        else -> throw GraphiteException(TorrentError(ErrorKind.UNKNOWN, "unknown method $method"))
    }

    private suspend fun unit(block: suspend () -> Unit): JsonElement {
        block()
        return JsonNull
    }

    companion object {
        const val MAX_SKEW_MS = 120_000L
    }
}
