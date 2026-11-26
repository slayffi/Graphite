package com.graphite.torrent.core.engine

import org.libtorrent4j.alerts.CacheFlushedAlert
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.AddSource
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.model.PeerEntry
import com.graphite.torrent.core.model.PeerSource
import com.graphite.torrent.core.model.PieceMapSnapshot
import com.graphite.torrent.core.model.PieceState
import com.graphite.torrent.core.model.PortStatus
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentMetadata
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.core.model.TrackerEntry
import com.graphite.torrent.core.model.TrackerStatus
import com.graphite.torrent.core.settings.DiskIoMode
import com.graphite.torrent.core.util.Base64Util
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.ErrorCode
import org.libtorrent4j.InfoHash
import org.libtorrent4j.LibTorrent
import org.libtorrent4j.MoveFlags
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.StorageMode
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.ExternalIpAlert
import org.libtorrent4j.alerts.FastresumeRejectedAlert
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.ListenFailedAlert
import org.libtorrent4j.alerts.ListenSucceededAlert
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.PortmapAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.SaveResumeDataFailedAlert
import org.libtorrent4j.alerts.StorageMovedAlert
import org.libtorrent4j.alerts.StorageMovedFailedAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentCheckedAlert
import org.libtorrent4j.alerts.TorrentDeletedAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.alerts.TorrentRemovedAlert
import org.libtorrent4j.swig.address
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.ip_filter
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.peer_info
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * [TorrentEngine] backed by libtorrent through libtorrent4j.
 *
 * Threading: libtorrent handles are thread safe, alerts arrive on libtorrent4j's alert thread,
 * the status loop runs on [Dispatchers.Default] and blocking calls run on [Dispatchers.IO].
 */
class LibtorrentEngine(
    private val resumeStore: ResumeStore,
    private val workDir: File,
    private val nativeExtractDir: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : TorrentEngine {

    // SessionManager's static initializer calls into the JNI library, so load it first.
    private val sm: SessionManager = run {
        NativeLibraries.ensureLoaded(nativeExtractDir)
        SessionManager(false)
    }
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val removed = ConcurrentHashMap.newKeySet<String>()
    private val cache = ConcurrentHashMap<String, CachedInfo>()
    private val pendingAdds = ConcurrentHashMap<String, CompletableDeferred<TorrentHandle>>()
    private val pendingResume = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val pendingFlush = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val lastAllTime = ConcurrentHashMap<String, LongArray>()

    private val _torrents = MutableStateFlow<Map<String, TorrentItem>>(emptyMap())
    private val _session = MutableStateFlow(SessionSnapshot())
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 512)

    override val torrents: StateFlow<Map<String, TorrentItem>> = _torrents.asStateFlow()
    override val session: StateFlow<SessionSnapshot> = _session.asStateFlow()
    override val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    @Volatile private var config: EngineConfig? = null
    @Volatile private var pauseReasons: Set<PauseReason> = emptySet()
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var sessionStartSec: Long = 0

    // Port state
    @Volatile private var listenPort = 0
    @Volatile private var listening = false
    @Volatile private var listenFailed = false
    @Volatile private var listenSince = 0L
    @Volatile private var incomingSeen = false
    @Volatile private var portMapped = false
    @Volatile private var externalAddress = ""
    @Volatile private var portRetries = 0

    // Session counters
    @Volatile private var sessionDownloaded = 0L
    @Volatile private var sessionUploaded = 0L

    private class CachedInfo(
        @Volatile var totalSize: Long = 0,
        @Volatile var isPrivate: Boolean = false,
        @Volatile var savePath: String = "",
        @Volatile var magnetUri: String = "",
        @Volatile var name: String = "",
        @Volatile var pieceLength: Int = 0,
        @Volatile var pieceCount: Int = 0,
        @Volatile var originalTrackers: List<String> = emptyList(),
    )

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    override suspend fun start(config: EngineConfig) = withContext(Dispatchers.IO) {
        if (scope != null) return@withContext
        NativeLibraries.ensureLoaded(nativeExtractDir)
        if (!workDir.isDirectory) workDir.mkdirs()
        this@LibtorrentEngine.config = config
        listenPort = config.listenPort
        sessionStartSec = clock() / 1000

        val pack = buildPack(config)
        val params = SessionParams(pack)
        if (config.settings.diskIoMode == DiskIoMode.POSIX) params.setPosixDiskIO()
        sm.addListener(listener)
        sm.start(params)
        // SessionManager.start() writes its own alert mask; re-apply ours afterwards.
        sm.applySettings(pack)
        applyPeerClassFilter(config.settings.zeroUpload)
        applySessionPause()

        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = engineScope
        restoreTorrents()
        engineScope.launch { statusLoop() }
        engineScope.launch { resumeLoop() }
        _session.value = _session.value.copy(running = true, libtorrentVersion = LibTorrent.version())
    }

    override suspend fun stop() {
        val s = scope ?: return
        saveAllResumeData()
        withContext(Dispatchers.IO) {
            sm.removeListener(listener)
            sm.stop()
        }
        s.cancel()
        scope = null
        handles.clear()
        _torrents.value = emptyMap()
        _session.value = _session.value.copy(running = false, downloadRate = 0, uploadRate = 0)
    }

    override fun updateConfig(config: EngineConfig) {
        val previous = this.config
        this.config = config
        if (scope == null) return
        if (previous?.listenPort != config.listenPort) {
            listenPort = config.listenPort
            listening = false
            listenFailed = false
            incomingSeen = false
            portMapped = false
        }
        sm.applySettings(buildPack(config))
        if (previous?.settings?.zeroUpload != config.settings.zeroUpload) {
            applyPeerClassFilter(config.settings.zeroUpload)
            if (config.settings.zeroUpload) enforceZeroUploadOnFinished()
        }
        if (previous?.settings?.enablePex != config.settings.enablePex) {
            handles.values.forEach { h ->
                if (config.settings.enablePex) h.unsetFlags(TorrentFlags.DISABLE_PEX) else h.setFlags(TorrentFlags.DISABLE_PEX)
            }
        }
    }

    override fun setPauseReasons(reasons: Set<PauseReason>) {
        pauseReasons = reasons
        if (scope != null) applySessionPause()
        _session.value = _session.value.copy(pauseReasons = reasons)
    }

    private fun applySessionPause() {
        if (pauseReasons.isEmpty()) {
            if (sm.isPaused) sm.resume()
        } else if (!sm.isPaused) {
            sm.pause()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------------

    private fun buildPack(config: EngineConfig): SettingsPack {
        val spec = SettingsFactory.build(config)
        val pack = SettingsPack()
        spec.ints.forEach { (name, value) -> pack.setInteger(SettingNames.int(name), value) }
        spec.bools.forEach { (name, value) -> pack.setBoolean(SettingNames.bool(name), value) }
        spec.strings.forEach { (name, value) -> pack.setString(SettingNames.string(name), value) }
        pack.setInteger(settings_pack.int_types.alert_mask.swigValue(), alertMask())
        return pack
    }

    private fun alertMask(): Int = Alert.ERROR_NOTIFICATION
        .or_(Alert.STATUS_NOTIFICATION)
        .or_(Alert.STORAGE_NOTIFICATION)
        .or_(Alert.PORT_MAPPING_NOTIFICATION)
        .or_(Alert.PERFORMANCE_WARNING)
        .or_(Alert.IP_BLOCK_NOTIFICATION)
        .or_(Alert.TRACKER_NOTIFICATION)
        .or_(Alert.PEER_NOTIFICATION)
        .to_int()

    /**
     * libtorrent puts LAN peers into the "local" peer class whose peers ignore unchoke slots, i.e.
     * they would be unchoked (and could download from us) even with `unchoke_slots_limit = 0`.
     * In zero-upload mode every address is mapped to the global class only.
     */
    private fun applyPeerClassFilter(zeroUpload: Boolean) {
        val filter = ip_filter()
        val globalMask = 1L shl 0
        val localMask = 1L shl 2
        addRule(filter, "0.0.0.0", "255.255.255.255", globalMask)
        addRule(filter, "::", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", globalMask)
        if (!zeroUpload) {
            addRule(filter, "10.0.0.0", "10.255.255.255", localMask)
            addRule(filter, "172.16.0.0", "172.31.255.255", localMask)
            addRule(filter, "192.168.0.0", "192.168.255.255", localMask)
            addRule(filter, "169.254.0.0", "169.254.255.255", localMask)
            addRule(filter, "127.0.0.0", "127.255.255.255", localMask)
            addRule(filter, "fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", localMask)
            addRule(filter, "fe80::", "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff", localMask)
            addRule(filter, "::1", "::1", localMask)
        }
        sm.swig().set_peer_class_filter(filter)
    }

    private fun addRule(filter: ip_filter, first: String, last: String, flags: Long): Boolean {
        val ec = error_code()
        val a = address.from_string(first, ec)
        if (ec.failed()) return false
        val b = address.from_string(last, ec)
        if (ec.failed()) return false
        filter.add_rule(a, b, flags)
        return true
    }

    override fun setIpFilter(ranges: List<IpRange>) {
        val filter = ip_filter()
        for (r in ranges) addRule(filter, r.first, r.last, IP_FILTER_BLOCKED)
        sm.swig().set_ip_filter(filter)
    }

    // ---------------------------------------------------------------------------------------------
    // Restore / resume data
    // ---------------------------------------------------------------------------------------------

    private fun restoreTorrents() {
        for ((fileId, bytes) in resumeStore.loadAll()) {
            val ec = error_code()
            val swigParams = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(bytes), ec)
            if (ec.failed()) {
                resumeStore.quarantine(fileId)
                _events.tryEmit(EngineEvent.ResumeRejected(fileId, ErrorMapper.fromCode(ec.value(), ec.message())))
                continue
            }
            val params = AddTorrentParams(swigParams)
            val id = idOfParams(params) ?: fileId
            File(params.getSavePath()).mkdirs()
            val pex = config?.settings?.enablePex ?: true
            if (!pex) params.setFlags(params.getFlags().or_(TorrentFlags.DISABLE_PEX))
            cache.getOrPut(id) { CachedInfo() }.apply {
                savePath = params.getSavePath()
                name = params.getName()
            }
            sm.swig().async_add_torrent(params.swig())
        }
    }

    private suspend fun resumeLoop() {
        val flags = TorrentHandle.SAVE_INFO_DICT.or_(TorrentHandle.ONLY_IF_MODIFIED)
        while (scope?.isActive == true) {
            delay(RESUME_SAVE_INTERVAL_MS)
            for (h in handles.values) {
                runCatching { if (h.isValid && h.needSaveResumeData()) h.saveResumeData(flags) }
            }
        }
    }

    override suspend fun flush(id: String) {
        val handle = handles[id] ?: return
        val done = CompletableDeferred<Unit>()
        pendingFlush[id] = done
        try {
            withContext(Dispatchers.IO) { handle.flushCache() }
            // libtorrent answers with cache_flushed_alert once the blocks it held are on disk.
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) { done.await() }
        } finally {
            pendingFlush.remove(id)
        }
    }

    override suspend fun saveAllResumeData() {
        if (scope == null) return
        val waits = ArrayList<CompletableDeferred<Unit>>()
        val flags = TorrentHandle.SAVE_INFO_DICT.or_(TorrentHandle.FLUSH_DISK_CACHE)
        for ((id, h) in handles) {
            if (!h.isValid) continue
            val d = CompletableDeferred<Unit>()
            pendingResume[id] = d
            waits += d
            runCatching { h.saveResumeData(flags) }.onFailure { d.complete(Unit) }
        }
        withTimeoutOrNull(RESUME_FLUSH_TIMEOUT_MS) { waits.forEach { it.await() } }
        pendingResume.clear()
    }

    private fun requestResumeSave(h: TorrentHandle) {
        runCatching { if (h.isValid) h.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
    }

    // ---------------------------------------------------------------------------------------------
    // Alerts
    // ---------------------------------------------------------------------------------------------

    private val listener = object : AlertListener {
        override fun types(): IntArray = LISTENED_TYPES

        override fun alert(alert: Alert<*>) {
            try {
                handleAlert(alert)
            } catch (_: Throwable) {
                // Never let an exception kill libtorrent4j's alert thread.
            }
        }
    }

    private fun handleAlert(alert: Alert<*>) {
        when (alert) {
            is AddTorrentAlert -> onAdded(alert)
            is TorrentFinishedAlert -> onFinished(alert.handle())
            is SaveResumeDataAlert -> onResumeData(alert)
            is SaveResumeDataFailedAlert -> pendingResume.remove(idOf(alert.handle()))?.complete(Unit)
            is CacheFlushedAlert -> pendingFlush.remove(idOf(alert.handle()))?.complete(Unit)
            is MetadataReceivedAlert -> onMetadata(alert.handle())
            is TorrentErrorAlert -> emitFailure(alert, alert.error(), alert.filename())
            is FileErrorAlert -> emitFailure(alert, alert.error(), alert.filename())
            is FastresumeRejectedAlert -> {
                val id = idOf(alert.handle())
                _events.tryEmit(EngineEvent.ResumeRejected(id, errorOf(alert.error(), alert.filePath())))
            }
            is TorrentRemovedAlert -> {
                val id = TorrentMapping.idOf(alert.getInfoHashes())
                handles.remove(id)
                resumeStore.delete(id)
                _events.tryEmit(EngineEvent.Removed(id))
            }
            is TorrentDeletedAlert -> _events.tryEmit(EngineEvent.FilesDeleted(TorrentMapping.idOf(alert.getInfoHashes())))
            is StorageMovedAlert -> {
                val id = idOf(alert.handle())
                cache[id]?.savePath = alert.storagePath()
                requestResumeSave(alert.handle())
                _events.tryEmit(EngineEvent.Moved(id, alert.storagePath()))
            }
            is StorageMovedFailedAlert -> {
                val id = idOf(alert.handle())
                _events.tryEmit(EngineEvent.MoveFailed(id, errorOf(alert.error(), alert.filePath())))
            }
            is TorrentCheckedAlert -> {
                val h = alert.handle()
                _events.tryEmit(EngineEvent.Checked(idOf(h)))
                if (isZeroUpload()) enforceZeroUpload(h)
            }
            is ListenSucceededAlert -> {
                if (alert.port() > 0) {
                    listening = true
                    listenFailed = false
                    listenPort = alert.port()
                    if (listenSince == 0L) listenSince = clock()
                    _events.tryEmit(EngineEvent.ListenSucceeded(alert.port()))
                }
            }
            is ListenFailedAlert -> onListenFailed(alert)
            is PortmapAlert -> {
                portMapped = true
                _events.tryEmit(EngineEvent.PortMapped(alert.externalPort()))
            }
            is ExternalIpAlert -> externalAddress = alert.externalAddress().toString()
            else -> if (alert.type() == AlertType.INCOMING_CONNECTION) incomingSeen = true
        }
    }

    private fun onAdded(alert: AddTorrentAlert) {
        val error = alert.error()
        val params = alert.params()
        val paramsId = idOfParams(params)
        if (error.isError) {
            val failure = errorOf(error, params.getName())
            val id = paramsId ?: return
            val pending = pendingAdds.remove(id)
            if (pending != null) pending.completeExceptionally(GraphiteException(failure)) else
                _events.tryEmit(EngineEvent.Failed(id, params.getName(), failure))
            return
        }
        // alert.handle() points into the alert's memory, which libtorrent frees with the next batch of
        // alerts. Keep an owned copy of the handle.
        val id = idOf(alert.handle())
        val h = sm.find(Sha1Hash.parseHex(id))?.takeIf { it.isValid } ?: return
        handles[id] = h
        removed.remove(id)
        val info = cache.getOrPut(id) { CachedInfo() }
        info.savePath = runCatching { h.savePath() }.getOrDefault(params.getSavePath())
        h.torrentFile()?.let { ti -> fillCache(info, ti) }
        if (info.name.isEmpty()) info.name = params.getName()
        val pending = pendingAdds.remove(id) ?: paramsId?.let { pendingAdds.remove(it) }
        if (pending != null) {
            pending.complete(h)
            requestResumeSave(h)
        } else {
            _events.tryEmit(EngineEvent.Restored(id))
        }
    }

    private fun fillCache(info: CachedInfo, ti: TorrentInfo) {
        info.totalSize = ti.totalSize()
        info.isPrivate = ti.isPrivate
        info.pieceLength = ti.pieceLength()
        info.pieceCount = ti.numPieces()
        if (ti.name().isNotEmpty()) info.name = ti.name()
    }

    private fun onMetadata(h: TorrentHandle) {
        val id = idOf(h)
        val info = cache.getOrPut(id) { CachedInfo() }
        val ti = h.torrentFile()
        if (ti != null) {
            fillCache(info, ti)
            if (ti.isPrivate && info.originalTrackers.isNotEmpty()) {
                // Do not leak a private torrent to the public tracker list that was added for the magnet.
                h.replaceTrackers(info.originalTrackers.map { AnnounceEntry(it) })
            }
        }
        info.magnetUri = runCatching { h.makeMagnetUri() }.getOrDefault(info.magnetUri)
        requestResumeSave(h)
        _events.tryEmit(EngineEvent.MetadataReceived(id, info.name))
    }

    private fun onFinished(h: TorrentHandle) {
        val id = idOf(h)
        val status = runCatching { h.status() }.getOrNull()
        val completedSec = status?.completedTime() ?: 0L
        val fresh = completedSec == 0L || completedSec >= sessionStartSec - 5
        if (isZeroUpload()) enforceZeroUpload(h)
        requestResumeSave(h)
        _events.tryEmit(EngineEvent.Finished(id, cache[id]?.name ?: status?.name().orEmpty(), fresh))
    }

    private fun onResumeData(alert: SaveResumeDataAlert) {
        val h = alert.handle()
        val id = idOf(h)
        if (id !in removed && handles.containsKey(id)) {
            runCatching {
                val bytes = AddTorrentParams.writeResumeDataBuf(alert.params())
                resumeStore.save(id, bytes)
            }
        }
        pendingResume.remove(id)?.complete(Unit)
    }

    private fun emitFailure(alert: TorrentAlert<*>, error: ErrorCode, file: String) {
        val h = alert.handle()
        val id = idOf(h)
        _events.tryEmit(EngineEvent.Failed(id, cache[id]?.name.orEmpty(), errorOf(error, file)))
    }

    private fun onListenFailed(alert: ListenFailedAlert) {
        val error = alert.error()
        val cfg = config ?: return
        val isIpv4 = runCatching { alert.address().isV4 || alert.address().isUnspecified }.getOrDefault(true)
        if (!isIpv4) return
        if (cfg.settings.autoPort && ErrorMapper.isAddressInUse(error.getValue(), error.getMessage()) &&
            portRetries < MAX_PORT_RETRIES
        ) {
            portRetries++
            val newPort = randomPort()
            val updated = cfg.copy(listenPort = newPort)
            config = updated
            listenPort = newPort
            sm.applySettings(SettingsPack().apply {
                setString(settings_pack.string_types.listen_interfaces.swigValue(), SettingsFactory.listenInterfaces(updated))
            })
            _events.tryEmit(EngineEvent.PortChanged(newPort))
        } else {
            listenFailed = !listening
            _events.tryEmit(EngineEvent.ListenFailed(alert.port(), errorOf(error, alert.listenInterface())))
        }
    }

    private fun errorOf(code: ErrorCode, context: String): TorrentError {
        val base = ErrorMapper.fromCode(code.getValue(), code.getMessage())
        return if (context.isBlank()) base else base.copy(detail = "${base.detail} ($context)")
    }

    // ---------------------------------------------------------------------------------------------
    // Zero upload
    // ---------------------------------------------------------------------------------------------

    private fun isZeroUpload() = config?.settings?.zeroUpload ?: true

    /** After 100 % the torrent is paused and removed from the auto-managed queue: no seeding. */
    private fun enforceZeroUpload(h: TorrentHandle) {
        if (!h.isValid) return
        val st = runCatching { h.status() }.getOrNull() ?: return
        if (!st.isFinished) return
        val flags = st.flags()
        val autoManaged = flags.and_(TorrentFlags.AUTO_MANAGED).non_zero()
        val paused = flags.and_(TorrentFlags.PAUSED).non_zero()
        if (autoManaged) h.unsetFlags(TorrentFlags.AUTO_MANAGED)
        if (!paused) h.pause()
    }

    private fun enforceZeroUploadOnFinished() {
        handles.values.forEach { runCatching { enforceZeroUpload(it) } }
    }

    // ---------------------------------------------------------------------------------------------
    // Status loop
    // ---------------------------------------------------------------------------------------------

    private suspend fun statusLoop() {
        while (scope?.isActive == true) {
            runCatching { refresh() }
            delay(STATUS_INTERVAL_MS)
        }
    }

    private fun refresh() {
        val zero = isZeroUpload()
        val sessionPaused = pauseReasons.isNotEmpty()
        val items = HashMap<String, TorrentItem>(handles.size)
        var downRate = 0L
        var upRate = 0L
        var allTimeUp = 0L
        var active = 0
        for ((id, h) in handles) {
            if (!h.isValid) continue
            val st = runCatching { h.status() }.getOrNull() ?: continue
            if (zero && st.isFinished) {
                val f = st.flags()
                if (f.and_(TorrentFlags.AUTO_MANAGED).non_zero() || !f.and_(TorrentFlags.PAUSED).non_zero()) {
                    enforceZeroUpload(h)
                }
            }
            val item = toItem(id, h, st, sessionPaused)
            items[id] = item
            downRate += item.downloadRate
            upRate += item.uploadRate
            allTimeUp += item.uploadedPayload
            if (item.state == TorrentState.DOWNLOADING || item.state == TorrentState.METADATA ||
                item.state == TorrentState.CHECKING
            ) active++
            accountTraffic(id, st)
        }
        lastAllTime.keys.retainAll(items.keys)
        _torrents.value = items

        val portStatus = when {
            listenFailed && !listening -> PortStatus.FAILED
            incomingSeen -> PortStatus.OPEN
            portMapped -> PortStatus.MAPPED
            listening && clock() - listenSince > FIREWALL_GRACE_MS -> PortStatus.FIREWALLED
            else -> PortStatus.CHECKING
        }
        _session.value = _session.value.copy(
            downloadRate = downRate,
            uploadRate = upRate,
            sessionDownloaded = sessionDownloaded,
            sessionUploaded = sessionUploaded,
            allTimeUploaded = allTimeUp,
            activeTorrents = active,
            totalTorrents = items.size,
            dhtNodes = runCatching { sm.dhtNodes() }.getOrDefault(0L),
            listenPort = listenPort,
            portStatus = portStatus,
            externalAddress = externalAddress,
            pauseReasons = pauseReasons,
            zeroUpload = zero,
            running = true,
        )
    }

    private fun accountTraffic(id: String, st: TorrentStatus) {
        val down = st.allTimeDownload()
        val up = st.allTimeUpload()
        val last = lastAllTime[id]
        if (last != null) {
            sessionDownloaded += (down - last[0]).coerceAtLeast(0)
            sessionUploaded += (up - last[1]).coerceAtLeast(0)
            last[0] = down
            last[1] = up
        } else {
            lastAllTime[id] = longArrayOf(down, up)
        }
    }

    private fun toItem(id: String, h: TorrentHandle, st: TorrentStatus, sessionPaused: Boolean): TorrentItem {
        val info = cache.getOrPut(id) { CachedInfo() }
        if (info.totalSize == 0L && st.hasMetadata()) h.torrentFile()?.let { fillCache(info, it) }
        if (info.magnetUri.isEmpty()) info.magnetUri = runCatching { h.makeMagnetUri() }.getOrDefault("")
        val flags = st.flags()
        val paused = flags.and_(TorrentFlags.PAUSED).non_zero()
        val autoManaged = flags.and_(TorrentFlags.AUTO_MANAGED).non_zero()
        val error = st.errorCode()
        val lt = st.state()
        val state = when {
            error.isError -> TorrentState.ERROR
            st.isMovingStorage -> TorrentState.MOVING
            lt == TorrentStatus.State.CHECKING_FILES || lt == TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentState.CHECKING
            st.isFinished && st.hasMetadata() -> TorrentState.COMPLETED
            paused && autoManaged -> TorrentState.QUEUED
            paused || sessionPaused -> TorrentState.PAUSED
            lt == TorrentStatus.State.DOWNLOADING_METADATA -> TorrentState.METADATA
            else -> TorrentState.DOWNLOADING
        }
        val wanted = st.totalWanted()
        val done = st.totalWantedDone()
        val rate = st.downloadPayloadRate().toLong()
        val eta = if (state == TorrentState.DOWNLOADING && rate > 0 && wanted > done) (wanted - done) / rate else -1L
        val name = st.name().ifEmpty { info.name }.ifEmpty { id }
        if (name != id) info.name = name
        return TorrentItem(
            id = id,
            name = name,
            state = state,
            progress = if (state == TorrentState.COMPLETED) 1f else st.progress().coerceIn(0f, 1f),
            wantedBytes = wanted,
            doneBytes = done,
            totalBytes = info.totalSize,
            downloadRate = if (paused || sessionPaused) 0 else rate,
            uploadRate = st.uploadPayloadRate().toLong(),
            uploadedPayload = st.allTimeUpload(),
            downloadedPayload = st.allTimeDownload(),
            etaSeconds = eta,
            connectedPeers = (st.numPeers() - st.numSeeds()).coerceAtLeast(0),
            connectedSeeds = st.numSeeds(),
            totalPeers = st.numIncomplete().coerceAtLeast(st.listPeers() - st.listSeeds()).coerceAtLeast(0),
            totalSeeds = st.numComplete().coerceAtLeast(st.listSeeds()).coerceAtLeast(0),
            savePath = info.savePath,
            queuePosition = st.queuePosition(),
            sequential = flags.and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero(),
            hasMetadata = st.hasMetadata(),
            isPrivate = info.isPrivate,
            addedAt = st.addedTime() * 1000,
            completedAt = st.completedTime() * 1000,
            error = if (error.isError) ErrorMapper.fromCode(error.getValue(), error.getMessage()) else null,
            magnetUri = info.magnetUri,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------------------------------

    override suspend fun fetchMetadata(magnetUri: String, timeoutSeconds: Int): TorrentMetadata {
        if (scope == null) throw GraphiteException(ErrorKind.UNKNOWN, "engine is not running")
        val params = parseMagnet(magnetUri)
        val trackers = params.getTrackers()
        val tempDir = File(workDir, "metadata").apply { mkdirs() }
        val data = runInterruptible(Dispatchers.IO) {
            sm.fetchMagnet(magnetUri, timeoutSeconds, tempDir)
        } ?: throw GraphiteException(ErrorKind.METADATA_TIMEOUT, magnetUri.take(80))
        val info = TorrentMapping.parseTorrentInfo(data)
        return TorrentMapping.metadataOf(info, data, magnetUri, trackers)
    }

    override fun parseTorrentFile(data: ByteArray): TorrentMetadata {
        NativeLibraries.ensureLoaded(nativeExtractDir)
        val info = TorrentMapping.parseTorrentInfo(data)
        return TorrentMapping.metadataOf(info, data)
    }

    override fun metadata(id: String): TorrentMetadata? {
        val h = handles[id] ?: return null
        val ti = h.torrentFile() ?: return null
        val params = AddTorrentParams()
        params.setTorrentInfo(ti)
        params.setTrackers(runCatching { h.trackers().map { it.url() } }.getOrDefault(emptyList()))
        val data = runCatching { Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(params.swig())) }
            .getOrNull() ?: return null
        return TorrentMapping.metadataOf(ti, data, cache[id]?.magnetUri.orEmpty())
    }

    private fun parseMagnet(uri: String): AddTorrentParams {
        val ec = error_code()
        val swigParams = libtorrent.parse_magnet_uri(uri.trim(), ec)
        if (ec.failed()) throw GraphiteException(ErrorKind.INVALID_TORRENT, ec.message())
        return AddTorrentParams(swigParams)
    }

    // ---------------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------------

    override suspend fun add(request: AddRequest, defaultSavePath: String, extraTrackers: List<String>): String =
        withContext(Dispatchers.IO) {
            val cfg = config ?: throw GraphiteException(ErrorKind.UNKNOWN, "engine is not running")
            val params: AddTorrentParams
            var ti: TorrentInfo? = null
            when (val src = request.source) {
                is AddSource.Magnet -> params = parseMagnet(src.uri)
                is AddSource.TorrentFile -> {
                    val data = decodeBase64(src.dataBase64)
                    ti = TorrentMapping.parseTorrentInfo(data)
                    params = AddTorrentParams().also { it.setTorrentInfo(ti) }
                }
                is AddSource.Preview -> {
                    val data = decodeBase64(src.metadata.torrentDataBase64)
                    ti = TorrentMapping.parseTorrentInfo(data)
                    params = if (src.metadata.magnetUri.isNotBlank()) parseMagnet(src.metadata.magnetUri) else AddTorrentParams()
                    params.setTorrentInfo(ti)
                }
            }
            val id = idOfParams(params) ?: throw GraphiteException(ErrorKind.INVALID_TORRENT, "no info hash")
            if (handles.containsKey(id)) {
                throw GraphiteException(ErrorKind.DUPLICATE, cache[id]?.name ?: params.getName())
            }

            val savePath = (request.savePath ?: defaultSavePath).ifBlank { defaultSavePath }
            val dir = File(savePath)
            if (!dir.isDirectory && !dir.mkdirs()) throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, savePath)
            if (!dir.canWrite()) throw GraphiteException(ErrorKind.PERMISSION_DENIED, savePath)

            if (ti != null) {
                val priorities = request.filePriorities.orEmpty()
                val fs = ti.files()
                var wanted = 0L
                val arr = Array(fs.numFiles()) { i ->
                    val p = priorities[i] ?: FilePriority.NORMAL
                    if (p != FilePriority.SKIP && !fs.padFileAt(i)) wanted += fs.fileSize(i)
                    TorrentMapping.toLibtorrent(p)
                }
                if (priorities.isNotEmpty()) params.filePriorities(arr)
                val free = dir.usableSpace
                if (free in 1 until wanted) {
                    throw GraphiteException(ErrorKind.NO_SPACE, "need $wanted bytes, $free available in $savePath")
                }
            }

            params.setSavePath(dir.absolutePath)
            params.setStorageMode(if (cfg.settings.preallocate) StorageMode.STORAGE_MODE_ALLOCATE else StorageMode.STORAGE_MODE_SPARSE)

            var flags: torrent_flags_t = params.getFlags()
                .and_(TorrentFlags.PAUSED.inv())
                .and_(TorrentFlags.AUTO_MANAGED.inv())
                .and_(TorrentFlags.SEQUENTIAL_DOWNLOAD.inv())
                .or_(TorrentFlags.DUPLICATE_IS_ERROR)
            flags = if (request.startPaused) flags.or_(TorrentFlags.PAUSED) else flags.or_(TorrentFlags.AUTO_MANAGED)
            if (request.sequential ?: cfg.settings.sequentialByDefault) flags = flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            if (!cfg.settings.enablePex) flags = flags.or_(TorrentFlags.DISABLE_PEX)
            params.setFlags(flags)

            val isPrivate = ti?.isPrivate ?: false
            val original = params.getTrackers().toList()
            if (!isPrivate && extraTrackers.isNotEmpty()) {
                val merged = LinkedHashSet<String>(original)
                merged += extraTrackers
                params.setTrackers(merged.toList())
            }
            cache.getOrPut(id) { CachedInfo() }.apply {
                originalTrackers = original
                this.savePath = dir.absolutePath
                name = ti?.name() ?: params.getName().ifBlank { id }
                if (ti != null) fillCache(this, ti)
                if (request.source is AddSource.Magnet) magnetUri = (request.source as AddSource.Magnet).uri
            }
            removed.remove(id)

            val deferred = CompletableDeferred<TorrentHandle>()
            pendingAdds[id] = deferred
            sm.swig().async_add_torrent(params.swig())
            try {
                withTimeout(ADD_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                pendingAdds.remove(id)
                throw GraphiteException(ErrorKind.UNKNOWN, "add timed out", e)
            }
            _events.tryEmit(EngineEvent.Added(id, cache[id]?.name ?: id))
            id
        }

    override suspend fun addFromResumeData(data: ByteArray, safeRoot: String): String = withContext(Dispatchers.IO) {
        if (scope == null) throw GraphiteException(ErrorKind.UNKNOWN, "engine is not running")
        val ec = error_code()
        val swigParams = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(data), ec)
        if (ec.failed()) throw GraphiteException(ErrorKind.INVALID_TORRENT, ec.message())
        val params = AddTorrentParams(swigParams)
        val id = idOfParams(params) ?: throw GraphiteException(ErrorKind.INVALID_TORRENT, "no info hash")
        if (handles.containsKey(id)) throw GraphiteException(ErrorKind.DUPLICATE, params.getName())
        val root = File(safeRoot).apply { mkdirs() }
        val stored = File(params.getSavePath())
        // Network shares (\\host\share) and folders that do not exist here are not taken from the file.
        val usable = stored.isAbsolute && !params.getSavePath().startsWith("\\\\") && stored.isDirectory
        if (!usable) params.setSavePath(root.absolutePath)
        val insideRoot = runCatching { File(params.getSavePath()).canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) }.getOrDefault(false)
        if (!insideRoot) {
            params.setFlags(params.getFlags().and_(TorrentFlags.AUTO_MANAGED.inv()).or_(TorrentFlags.PAUSED))
        }
        cache.getOrPut(id) { CachedInfo() }.apply {
            this.savePath = params.getSavePath()
            name = params.getName()
        }
        val deferred = CompletableDeferred<TorrentHandle>()
        pendingAdds[id] = deferred
        sm.swig().async_add_torrent(params.swig())
        withTimeout(ADD_TIMEOUT_MS) { deferred.await() }
        id
    }

    override fun resumeData(id: String): ByteArray? = resumeStore.load(id)

    private fun decodeBase64(text: String): ByteArray = try {
        Base64Util.decode(text)
    } catch (e: IllegalArgumentException) {
        throw GraphiteException(ErrorKind.INVALID_TORRENT, "bad encoding", e)
    }

    private fun handle(id: String): TorrentHandle =
        handles[id]?.takeIf { it.isValid } ?: throw GraphiteException(ErrorKind.UNKNOWN, "unknown torrent $id")

    override fun pause(id: String) {
        val h = handle(id)
        h.unsetFlags(TorrentFlags.AUTO_MANAGED)
        h.pause()
        requestResumeSave(h)
    }

    override fun resume(id: String) {
        val h = handle(id)
        val st = h.status()
        if (st.errorCode().isError) h.swig().clear_error()
        if (isZeroUpload() && st.isFinished && st.hasMetadata()) return
        h.setFlags(TorrentFlags.AUTO_MANAGED)
        h.resume()
        requestResumeSave(h)
    }

    override fun remove(id: String, deleteFiles: Boolean) {
        val h = handles.remove(id) ?: return
        removed += id
        lastAllTime.remove(id)
        pendingResume.remove(id)?.complete(Unit)
        resumeStore.delete(id)
        if (deleteFiles) sm.remove(h, SessionHandle.DELETE_FILES) else sm.remove(h)
        _torrents.value = _torrents.value - id
    }

    override fun recheck(id: String) {
        val h = handle(id)
        if (h.status().errorCode().isError) h.swig().clear_error()
        h.forceRecheck()
    }

    override fun move(id: String, targetDir: String) {
        val dir = File(targetDir)
        if (!dir.isDirectory && !dir.mkdirs()) throw GraphiteException(ErrorKind.FOLDER_UNAVAILABLE, targetDir)
        if (!dir.canWrite()) throw GraphiteException(ErrorKind.PERMISSION_DENIED, targetDir)
        handle(id).moveStorage(dir.absolutePath, MoveFlags.FAIL_IF_EXIST)
    }

    override fun setFilePriorities(id: String, priorities: Map<Int, FilePriority>) {
        val h = handle(id)
        val current = h.filePriorities()
        if (current.isEmpty()) return
        val updated = Array(current.size) { i -> priorities[i]?.let(TorrentMapping::toLibtorrent) ?: current[i] }
        h.prioritizeFiles(updated)
        val st = h.status()
        if (st.isFinished) return
        // A completed (paused) torrent that gets new files selected starts downloading again.
        val flags = st.flags()
        val wasCompletedPause = flags.and_(TorrentFlags.PAUSED).non_zero() && !flags.and_(TorrentFlags.AUTO_MANAGED).non_zero() &&
            st.totalWantedDone() > 0 && updated.any { it != Priority.IGNORE }
        if (wasCompletedPause && priorities.values.any { it != FilePriority.SKIP }) {
            h.setFlags(TorrentFlags.AUTO_MANAGED)
            h.resume()
        }
        requestResumeSave(h)
    }

    override fun setSequential(id: String, enabled: Boolean) {
        val h = handle(id)
        if (enabled) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        requestResumeSave(h)
    }

    override fun setQueuePosition(id: String, position: Int) {
        val h = handle(id)
        h.queuePositionSet(position.coerceAtLeast(0))
    }

    override fun addTrackers(id: String, urls: List<String>) {
        val h = handle(id)
        val existing = h.trackers().map { it.url() }.toSet()
        urls.filter { it !in existing }.forEach { h.addTracker(AnnounceEntry(it)) }
        requestResumeSave(h)
    }

    override fun removeTrackers(id: String, urls: List<String>) {
        val h = handle(id)
        val remove = urls.toSet()
        h.replaceTrackers(h.trackers().filter { it.url() !in remove })
        requestResumeSave(h)
    }

    override fun reannounce(id: String) {
        val h = handle(id)
        h.forceReannounce()
        runCatching { h.forceDHTAnnounce() }
    }

    // ---------------------------------------------------------------------------------------------
    // Details
    // ---------------------------------------------------------------------------------------------

    override fun files(id: String): List<FileEntry> {
        val h = handles[id] ?: return emptyList()
        val ti = h.torrentFile() ?: return emptyList()
        val fs = ti.files()
        val progress = h.fileProgress(TorrentHandle.PIECE_GRANULARITY)
        val priorities = h.filePriorities()
        val result = ArrayList<FileEntry>(fs.numFiles())
        for (i in 0 until fs.numFiles()) {
            if (fs.padFileAt(i)) continue
            result += FileEntry(
                index = i,
                path = fs.filePath(i).replace('\\', '/'),
                size = fs.fileSize(i),
                downloaded = progress.getOrElse(i) { 0L },
                priority = priorities.getOrNull(i)?.let(TorrentMapping::fromLibtorrent) ?: FilePriority.NORMAL,
            )
        }
        return result
    }

    override fun peers(id: String): List<PeerEntry> {
        val h = handles[id] ?: return emptyList()
        return h.peerInfo().map { p ->
            val flags = p.flags()
            val source = p.source().toInt() and 0xFF
            val sources = buildSet {
                if (source and PeerBits.SRC_TRACKER != 0) add(PeerSource.TRACKER)
                if (source and PeerBits.SRC_DHT != 0) add(PeerSource.DHT)
                if (source and PeerBits.SRC_PEX != 0) add(PeerSource.PEX)
                if (source and PeerBits.SRC_LSD != 0) add(PeerSource.LSD)
                if (source and PeerBits.SRC_RESUME != 0) add(PeerSource.RESUME)
                if (source and PeerBits.SRC_INCOMING != 0) add(PeerSource.INCOMING)
                if (p.connectionType() != org.libtorrent4j.PeerInfo.ConnectionType.STANDARD_BITTORRENT) add(PeerSource.WEB_SEED)
            }
            PeerEntry(
                address = p.ip(),
                client = p.client().ifBlank { "—" },
                downloadRate = p.downSpeed().toLong(),
                uploadRate = p.upSpeed().toLong(),
                progress = p.progress().coerceIn(0f, 1f),
                downloaded = p.totalDownload(),
                uploaded = p.totalUpload(),
                sources = sources,
                isSeed = flags and PeerBits.SEED != 0,
                isUtp = flags and PeerBits.UTP != 0,
                isEncrypted = flags and (PeerBits.RC4 or PeerBits.PLAINTEXT_ENCRYPTED) != 0,
                isIncoming = flags and PeerBits.OUTGOING == 0,
            )
        }.sortedByDescending { it.downloadRate }
    }

    override fun trackers(id: String): List<TrackerEntry> {
        val h = handles[id] ?: return emptyList()
        val st = runCatching { h.status() }.getOrNull()
        val cfg = config?.settings
        val result = ArrayList<TrackerEntry>()
        val isPrivate = cache[id]?.isPrivate ?: false
        fun pseudo(name: String, enabled: Boolean) = TrackerEntry(
            url = name, tier = -1,
            status = if (enabled && !isPrivate) TrackerStatus.WORKING else TrackerStatus.DISABLED,
            message = "", seeds = 0, peers = 0, downloaded = 0, isPseudo = true,
        )
        result += pseudo("DHT", (cfg?.enableDht ?: true) && (st?.announcingToDht() ?: true))
        result += pseudo("PEX", cfg?.enablePex ?: true)
        result += pseudo("LSD", (cfg?.enableLsd ?: true) && (st?.announcingToLsd() ?: true))
        for (entry in h.trackers()) {
            val swigEntry = entry.swig()
            val endpoints = swigEntry.getEndpoints()
            var status = TrackerStatus.NOT_CONTACTED
            var message = ""
            var seeds = -1
            var peers = -1
            var downloaded = -1
            for (i in 0 until endpoints.size) {
                val ep = endpoints.get(i)
                if (!ep.getEnabled()) continue
                for (ih in listOf(ep.get_infohash_v1(), ep.get_infohash_v2())) {
                    seeds = maxOf(seeds, ih.getScrape_complete())
                    peers = maxOf(peers, ih.getScrape_incomplete())
                    downloaded = maxOf(downloaded, ih.getScrape_downloaded())
                    val lastError = ih.getLast_error()
                    when {
                        ih.getUpdating() -> status = TrackerStatus.UPDATING
                        ih.getFails() > 0 && lastError.failed() -> if (status != TrackerStatus.WORKING && status != TrackerStatus.UPDATING) {
                            status = TrackerStatus.ERROR
                            message = lastError.message().ifBlank { ih.getMessage() }
                        }
                        ih.getStart_sent() -> if (status != TrackerStatus.UPDATING) {
                            status = TrackerStatus.WORKING
                            if (ih.getMessage().isNotBlank()) message = ih.getMessage()
                        }
                    }
                }
            }
            result += TrackerEntry(
                url = entry.url(),
                tier = entry.tier(),
                status = status,
                message = message,
                seeds = seeds.coerceAtLeast(0),
                peers = peers.coerceAtLeast(0),
                downloaded = downloaded.coerceAtLeast(0),
            )
        }
        return result
    }

    override fun pieces(id: String): PieceMapSnapshot {
        val h = handles[id] ?: return PieceMapSnapshot.EMPTY
        val ti = h.torrentFile() ?: return PieceMapSnapshot.EMPTY
        val count = ti.numPieces()
        val st = h.status(TorrentHandle.QUERY_PIECES)
        val have = st.pieces()
        val priorities = runCatching { h.piecePriorities() }.getOrNull()
        val queue = h.swig().get_download_queue()
        val downloading = HashSet<Int>(queue.size)
        for (i in 0 until queue.size) downloading += queue.get(i).getPiece_index()
        val states = ByteArray(count) { i ->
            when {
                have.getBit(i) -> PieceState.DONE
                i in downloading -> PieceState.DOWNLOADING
                priorities != null && i < priorities.size && priorities[i] != Priority.IGNORE -> PieceState.QUEUED
                else -> PieceState.MISSING
            }
        }
        return PieceMapSnapshot(count, ti.pieceLength(), states)
    }

    override fun filePath(id: String, fileIndex: Int): String? {
        val h = handles[id] ?: return null
        val ti = h.torrentFile() ?: return null
        val fs = ti.files()
        if (fileIndex !in 0 until fs.numFiles()) return null
        val base = cache[id]?.savePath?.ifEmpty { null } ?: h.savePath()
        return File(base, fs.filePath(fileIndex)).absolutePath
    }

    override fun pieceSpan(id: String, fileIndex: Int, offset: Long, length: Long): IntRange? {
        val h = handles[id] ?: return null
        val ti = h.torrentFile() ?: return null
        val fs = ti.files()
        if (fileIndex !in 0 until fs.numFiles()) return null
        val size = fs.fileSize(fileIndex)
        if (size <= 0) return null
        val start = offset.coerceIn(0, size - 1)
        val end = (offset + length.coerceAtLeast(1) - 1).coerceIn(start, size - 1)
        val pieceLength = ti.pieceLength().toLong()
        val base = fs.fileOffset(fileIndex)
        val first = ((base + start) / pieceLength).toInt()
        val last = ((base + end) / pieceLength).toInt().coerceAtMost(ti.numPieces() - 1)
        return first..last
    }

    override fun havePiece(id: String, piece: Int): Boolean = handles[id]?.havePiece(piece) ?: false

    override fun prioritizePieces(id: String, pieces: IntRange, deadlineMs: Int) {
        val h = handles[id] ?: return
        var step = 0
        for (p in pieces) {
            if (!h.havePiece(p)) h.setPieceDeadline(p, deadlineMs + step * 50)
            step++
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Test hook: applies raw libtorrent settings on top of the generated ones. */
    internal fun applyRawSettings(pack: SettingsPack) {
        sm.applySettings(pack)
    }

    private fun idOf(h: TorrentHandle): String = TorrentMapping.idOf(InfoHash(h.swig().info_hashes()))

    private fun idOfParams(params: AddTorrentParams): String? {
        val ti = params.getTorrentInfo()
        val hashes = if (ti != null && ti.isValid) ti.infoHashes() else params.getInfoHashes()
        if (!hashes.hasV1() && !hashes.hasV2()) return null
        return TorrentMapping.idOf(hashes)
    }

    private fun randomPort(): Int = Random.nextInt(PORT_RANGE_START, PORT_RANGE_END)

    /** Peer flag bits taken from the native constants once, so they always match libtorrent. */
    private object PeerBits {
        val SEED = peer_info.seed.to_int()
        val UTP = peer_info.utp_socket.to_int()
        val RC4 = peer_info.rc4_encrypted.to_int()
        val PLAINTEXT_ENCRYPTED = peer_info.plaintext_encrypted.to_int()
        val OUTGOING = peer_info.outgoing_connection.to_int()
        val SRC_TRACKER = peer_info.tracker.to_int()
        val SRC_DHT = peer_info.dht.to_int()
        val SRC_PEX = peer_info.pex.to_int()
        val SRC_LSD = peer_info.lsd.to_int()
        val SRC_RESUME = peer_info.resume_data.to_int()
        val SRC_INCOMING = peer_info.incoming.to_int()
    }

    companion object {
        const val STATUS_INTERVAL_MS = 1000L
        const val RESUME_SAVE_INTERVAL_MS = 30_000L
        const val RESUME_FLUSH_TIMEOUT_MS = 8_000L
        const val ADD_TIMEOUT_MS = 30_000L
        const val FIREWALL_GRACE_MS = 120_000L
        const val MAX_PORT_RETRIES = 10
        const val PORT_RANGE_START = 20_000
        const val PORT_RANGE_END = 60_000
        private const val IP_FILTER_BLOCKED = 1L

        private const val FLUSH_TIMEOUT_MS = 30_000L

        /** Picks a random port in the dynamic range; does not touch native code. */
        fun randomListenPort(): Int = Random.nextInt(PORT_RANGE_START, PORT_RANGE_END)

        // Lazy: AlertType's static initializer calls into the native library.
        private val LISTENED_TYPES by lazy { intArrayOf(
            AlertType.ADD_TORRENT.swig(),
            AlertType.TORRENT_FINISHED.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(),
            AlertType.CACHE_FLUSHED.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.FILE_ERROR.swig(),
            AlertType.FASTRESUME_REJECTED.swig(),
            AlertType.TORRENT_REMOVED.swig(),
            AlertType.TORRENT_DELETED.swig(),
            AlertType.STORAGE_MOVED.swig(),
            AlertType.STORAGE_MOVED_FAILED.swig(),
            AlertType.TORRENT_CHECKED.swig(),
            AlertType.LISTEN_SUCCEEDED.swig(),
            AlertType.LISTEN_FAILED.swig(),
            AlertType.PORTMAP.swig(),
            AlertType.EXTERNAL_IP.swig(),
            AlertType.INCOMING_CONNECTION.swig(),
        ) }
    }
}

/** Resolves setting names to libtorrent indices by reflection on the generated swig enums (cached). */
internal object SettingNames {
    private val ints = ConcurrentHashMap<String, Int>()
    private val bools = ConcurrentHashMap<String, Int>()
    private val strings = ConcurrentHashMap<String, Int>()

    fun int(name: String): Int = ints.getOrPut(name) {
        (settings_pack.int_types::class.java.getField(name).get(null) as settings_pack.int_types).swigValue()
    }

    fun bool(name: String): Int = bools.getOrPut(name) {
        (settings_pack.bool_types::class.java.getField(name).get(null) as settings_pack.bool_types).swigValue()
    }

    fun string(name: String): Int = strings.getOrPut(name) {
        (settings_pack.string_types::class.java.getField(name).get(null) as settings_pack.string_types).swigValue()
    }
}
