package com.graphite.torrent.data

import com.graphite.torrent.core.controller.ControllerNotice
import com.graphite.torrent.core.controller.TorrentController
import com.graphite.torrent.core.engine.EngineConfig
import com.graphite.torrent.core.engine.EngineEvent
import com.graphite.torrent.core.engine.ErrorMapper
import com.graphite.torrent.core.engine.IpFilterParser
import com.graphite.torrent.core.engine.LibtorrentEngine
import com.graphite.torrent.core.engine.TorrentEngine
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.AddSource
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.HistoryEvent
import com.graphite.torrent.core.model.LogLevel
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.model.PeerEntry
import com.graphite.torrent.core.model.PieceMapSnapshot
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.SpeedSample
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentMetadata
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.core.model.TrackerEntry
import com.graphite.torrent.core.net.NetworkInterfaces
import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.core.settings.SpeedProfile
import com.graphite.torrent.core.trackers.TrackerList
import com.graphite.torrent.core.util.Base64Util
import com.graphite.torrent.core.util.MagnetLink
import com.graphite.torrent.data.db.Torrent
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.repo.HistoryRepository
import com.graphite.torrent.data.repo.StatsRepository
import com.graphite.torrent.data.repo.TorrentMetaRepository
import com.graphite.torrent.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime adjustments applied on top of the user settings: scheduler limits, battery profile and
 * the resolved VPN interface addresses.
 */
data class RuntimeOverrides(
    val profile: SpeedProfile? = null,
    val downloadLimitKbps: Int? = null,
    val uploadLimitKbps: Int? = null,
    val bindAddresses: List<String> = emptyList(),
    val killSwitchBlocked: Boolean = false,
)

/** [TorrentController] running the engine in this process. */
class LocalTorrentController(
    private val engine: TorrentEngine,
    private val settingsRepository: SettingsRepository,
    private val meta: TorrentMetaRepository,
    private val stats: StatsRepository,
    private val eventLog: EventLogRepository,
    private val history: HistoryRepository,
    private val storage: PlatformStorage,
    private val scope: CoroutineScope,
    private val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
    private val clock: () -> Long = System::currentTimeMillis,
) : TorrentController {

    override val isRemote: Boolean = false

    private val pauseReasons = MutableStateFlow<Set<PauseReason>>(emptySet())
    private val overrides = MutableStateFlow(RuntimeOverrides())
    private val _speed = MutableStateFlow<List<SpeedSample>>(emptyList())
    private val _notices = MutableSharedFlow<ControllerNotice>(extraBufferCapacity = 64)
    private val lastFailureNotice = ConcurrentHashMap<String, Long>()
    private val jobs = ArrayList<Job>()
    @Volatile private var started = false
    private val lifecycle = Mutex()

    override val session: StateFlow<SessionSnapshot> = engine.session
    override val speedHistory: StateFlow<List<SpeedSample>> = _speed.asStateFlow()
    override val notices: SharedFlow<ControllerNotice> = _notices.asSharedFlow()

    /** Completed downloads whose files are being copied to their final folder right now. */
    private val relocating = MutableStateFlow<Set<String>>(emptySet())

    override val torrents: StateFlow<List<TorrentItem>> =
        combine(engine.torrents, meta.rows, meta.tags, relocating) { live, rows, tags, moving -> merge(live, rows, tags, moving) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val runtimeOverrides: StateFlow<RuntimeOverrides> = overrides.asStateFlow()

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts the engine once. Safe to call from several places at the same time (on Android the
     * application and the activity both ask for it right after launch).
     */
    suspend fun start() = lifecycle.withLock {
        if (started) return@withLock
        started = true
        var settings = settingsRepository.current()
        if (settings.listenPort == 0) {
            val port = LibtorrentEngine.randomListenPort()
            settings = settingsRepository.update { it.copy(listenPort = port) }
        }
        refreshInterfaceBinding(settings, initial = true)
        engine.start(buildConfig(settings, overrides.value))
        engine.setPauseReasons(pauseReasons.value)
        eventLog.log(EventType.ENGINE_STARTED, subject = engine.session.value.libtorrentVersion)

        jobs += scope.launch { engine.events.collect { runCatching { onEvent(it) } } }
        jobs += scope.launch {
            combine(settingsRepository.settings, overrides) { s, o -> buildConfig(s, o) }
                .drop(1)
                .distinctUntilChanged()
                .collect { engine.updateConfig(it) }
        }
        jobs += scope.launch { pauseReasons.collect { engine.setPauseReasons(it) } }
        jobs += scope.launch { sampleSpeed() }
        jobs += scope.launch { recordStats() }
        jobs += scope.launch { monitorInterfaces() }
        jobs += scope.launch { updateTrackersPeriodically() }
        jobs += scope.launch {
            settingsRepository.settings.map { it.ipFilterEnabled to it.ipFilterPath }
                .distinctUntilChanged()
                .collect { (enabled, path) -> loadIpFilter(enabled, path) }
        }
    }

    suspend fun stop() = lifecycle.withLock {
        if (!started) return@withLock
        flushStats()
        jobs.forEach { it.cancel() }
        jobs.clear()
        engine.stop()
        eventLog.log(EventType.ENGINE_STOPPED)
        started = false
    }

    fun buildConfig(settings: AppSettings, o: RuntimeOverrides): EngineConfig = EngineConfig(
        settings = settings,
        listenPort = settings.listenPort,
        cpuCores = cpuCores,
        profileOverride = o.profile,
        downloadLimitOverrideKbps = o.downloadLimitKbps,
        uploadLimitOverrideKbps = o.uploadLimitKbps,
        bindAddresses = o.bindAddresses,
        killSwitchBlocked = o.killSwitchBlocked,
    )

    /** Scheduler / battery rules use this to adjust the engine without touching user settings. */
    fun setOverrides(profile: SpeedProfile?, downloadLimitKbps: Int?, uploadLimitKbps: Int?) {
        overrides.update { it.copy(profile = profile, downloadLimitKbps = downloadLimitKbps, uploadLimitKbps = uploadLimitKbps) }
    }

    override fun setPauseReason(reason: PauseReason, active: Boolean) {
        val before = pauseReasons.value
        pauseReasons.update { if (active) it + reason else it - reason }
        val after = pauseReasons.value
        if (before.isEmpty() && after.isNotEmpty()) {
            scope.launch { eventLog.log(EventType.SESSION_PAUSED, subject = after.joinToString { it.name }) }
        } else if (before.isNotEmpty() && after.isEmpty()) {
            scope.launch { eventLog.log(EventType.SESSION_RESUMED) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // List merge
    // ---------------------------------------------------------------------------------------------

    private fun merge(
        live: Map<String, TorrentItem>,
        rows: Map<String, Torrent>,
        tags: Map<String, List<String>>,
        moving: Set<String>,
    ): List<TorrentItem> {
        val result = ArrayList<TorrentItem>(live.size + 8)
        for ((id, item) in live) {
            val row = rows[id]
            result += item.copy(
                // Shown as "moving" (and keeps the Android foreground service alive) while files are copied.
                state = if (id in moving) TorrentState.MOVING else item.state,
                category = row?.category,
                tags = tags[id].orEmpty(),
                addedAt = row?.added_at?.takeIf { it > 0 } ?: item.addedAt,
                completedAt = if (item.completedAt > 0) item.completedAt else row?.completed_at ?: 0,
                finalLocation = row?.final_location,
            )
        }
        // Completed downloads that were relocated out of the engine (Android SAF folders).
        for ((id, row) in rows) {
            if (id in live || row.final_location == null) continue
            result += archivedItem(row, tags[id].orEmpty())
        }
        result.sortByDescending { it.addedAt }
        return result
    }

    private fun archivedItem(row: Torrent, tags: List<String>) = TorrentItem(
        id = row.id,
        name = row.name,
        state = TorrentState.COMPLETED,
        progress = 1f,
        wantedBytes = row.total_size,
        doneBytes = row.total_size,
        totalBytes = row.total_size,
        downloadRate = 0,
        uploadRate = 0,
        uploadedPayload = 0,
        downloadedPayload = row.total_size,
        etaSeconds = -1,
        connectedPeers = 0,
        connectedSeeds = 0,
        totalPeers = 0,
        totalSeeds = 0,
        savePath = row.final_location.orEmpty(),
        queuePosition = -1,
        sequential = false,
        hasMetadata = true,
        isPrivate = false,
        addedAt = row.added_at,
        completedAt = row.completed_at,
        category = row.category,
        tags = tags,
        finalLocation = row.final_location,
        magnetUri = row.source.takeIf { it.startsWith("magnet:") }.orEmpty(),
    )

    private fun isLive(id: String) = engine.torrents.value.containsKey(id)

    // ---------------------------------------------------------------------------------------------
    // Engine events
    // ---------------------------------------------------------------------------------------------

    private suspend fun onEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Restored -> {
                val item = engine.torrents.value[event.id]
                meta.ensureExists(event.id, item?.name ?: event.id, item?.addedAt?.takeIf { it > 0 } ?: clock(), item?.savePath.orEmpty())
            }
            is EngineEvent.MetadataReceived -> {
                val item = engine.torrents.value[event.id]
                meta.updateInfo(event.id, event.name, item?.savePath.orEmpty(), item?.totalBytes ?: 0)
            }
            is EngineEvent.Finished -> onFinished(event)
            is EngineEvent.Failed -> {
                eventLog.error(EventType.TORRENT_ERROR, event.name, event.error, event.id)
                val now = clock()
                val last = lastFailureNotice[event.id] ?: 0
                if (now - last > 60_000) {
                    lastFailureNotice[event.id] = now
                    _notices.tryEmit(ControllerNotice.Failed(event.id, event.name, event.error))
                }
            }
            is EngineEvent.Moved -> {
                meta.setSavePath(event.id, event.path)
                val name = engine.torrents.value[event.id]?.name ?: event.id
                eventLog.log(EventType.TORRENT_MOVED, name, event.path, event.id)
                _notices.tryEmit(ControllerNotice.Moved(event.id, name, event.path))
            }
            is EngineEvent.MoveFailed -> {
                val name = engine.torrents.value[event.id]?.name ?: event.id
                eventLog.error(EventType.TORRENT_MOVE_FAILED, name, event.error, event.id)
                _notices.tryEmit(ControllerNotice.Failed(event.id, name, event.error))
            }
            is EngineEvent.ResumeRejected -> eventLog.log(
                EventType.RESUME_REJECTED, event.id, "${event.error.kind}: ${event.error.detail}", event.id, LogLevel.WARNING,
            )
            is EngineEvent.PortChanged -> {
                settingsRepository.update { it.copy(listenPort = event.port) }
                eventLog.log(EventType.PORT_CHANGED, event.port.toString(), level = LogLevel.WARNING)
            }
            is EngineEvent.ListenFailed -> eventLog.error(EventType.LISTEN_FAILED, event.port.toString(), event.error)
            is EngineEvent.PortMapped -> eventLog.log(EventType.PORT_MAPPED, event.externalPort.toString())
            is EngineEvent.Added, is EngineEvent.Removed, is EngineEvent.FilesDeleted,
            is EngineEvent.Checked, is EngineEvent.ListenSucceeded -> Unit
        }
    }

    private suspend fun onFinished(event: EngineEvent.Finished) {
        val item = engine.torrents.value[event.id]
        val row = meta.get(event.id)
        if (row == null) meta.ensureExists(event.id, event.name, clock(), item?.savePath.orEmpty())
        val firstTime = meta.markCompleted(event.id, clock())
        val justCompleted = firstTime && event.fresh
        if (justCompleted) {
            val size = item?.wantedBytes ?: 0
            stats.incrementCompleted()
            history.record(event.id, event.name, size, HistoryEvent.COMPLETED, item?.savePath.orEmpty())
            eventLog.log(EventType.TORRENT_COMPLETED, event.name, torrentId = event.id)
            _notices.tryEmit(ControllerNotice.Completed(event.id, event.name))
        }
        if (item == null) return
        // Also after a restart: a copy that was interrupted (app killed, folder unavailable) is tried again.
        val pending = !justCompleted && storage.needsRelocation(item, settingsRepository.current())
        if (!justCompleted && !pending) return
        if (!markRelocating(item.id)) return
        // Copying can take minutes: do not hold up the engine's event queue.
        scope.launch {
            try {
                handleCompletionStorage(item)
            } finally {
                relocating.update { it - item.id }
            }
        }
    }

    private fun markRelocating(id: String): Boolean {
        var added = false
        relocating.update { if (id in it) it else (it + id).also { added = true } }
        return added
    }

    private suspend fun handleCompletionStorage(item: TorrentItem) {
        val settings = settingsRepository.current()
        val files = runCatching { engine.files(item.id) }.getOrDefault(emptyList())
        val result = try {
            // The last blocks may still be in the engine's write queue when "finished" arrives: get them on
            // disk before the platform copies or moves the files.
            engine.flush(item.id)
            storage.afterCompletion(item, files, settings)
        } catch (e: Exception) {
            val error = ErrorMapper.fromException(e)
            eventLog.error(EventType.TORRENT_MOVE_FAILED, item.name, error, item.id)
            _notices.tryEmit(ControllerNotice.Failed(item.id, item.name, error))
            return
        }
        when (result) {
            CompletionResult.Keep -> Unit
            is CompletionResult.MoveTo -> runCatching { engine.move(item.id, result.path) }.onFailure {
                eventLog.error(EventType.TORRENT_MOVE_FAILED, item.name, ErrorMapper.fromException(it), item.id)
            }
            is CompletionResult.Relocated -> {
                // The files now live elsewhere: drop the torrent from the engine, keep it as an archive row.
                withContext(Dispatchers.IO) { engine.remove(item.id, deleteFiles = false) }
                meta.updateInfo(item.id, item.name, item.savePath, item.wantedBytes)
                meta.setFinalLocation(item.id, result.location)
                eventLog.log(EventType.TORRENT_RELOCATED, item.name, result.location, item.id)
                _notices.tryEmit(ControllerNotice.Moved(item.id, item.name, result.location))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------------

    override suspend fun previewMagnet(uri: String): TorrentMetadata {
        val link = MagnetLink.parse(uri) ?: throw GraphiteException(ErrorKind.INVALID_TORRENT, uri.take(80))
        if (isLive(link.id)) throw GraphiteException(ErrorKind.DUPLICATE, link.displayName ?: link.id)
        return engine.fetchMetadata(uri.trim(), METADATA_TIMEOUT_SECONDS)
    }

    override suspend fun previewTorrentFile(data: ByteArray): TorrentMetadata =
        withContext(Dispatchers.IO) { engine.parseTorrentFile(data) }

    override suspend fun add(request: AddRequest): String {
        val settings = settingsRepository.current()
        val savePath = storage.resolveSavePath(request.savePath.orEmpty(), settings)
        checkDestination(request, savePath)
        val extra = if (settings.addPublicTrackers) settings.trackerList else emptyList()
        val id = engine.add(
            // The engine needs the real folder: a chosen Android folder arrives here as a content:// URI
            // and is resolved by the platform storage above.
            request.copy(savePath = savePath, startPaused = request.startPaused || settings.startPaused),
            savePath,
            extra,
        )
        val item = engine.torrents.value[id]
        val name = item?.name ?: when (val src = request.source) {
            is AddSource.Preview -> src.metadata.name
            is AddSource.Magnet -> MagnetLink.parse(src.uri)?.displayName ?: id
            is AddSource.TorrentFile -> id
        }
        val size = (request.source as? AddSource.Preview)?.metadata?.totalSize ?: item?.totalBytes ?: 0
        val sourceText = when (val src = request.source) {
            is AddSource.Magnet -> src.uri
            is AddSource.Preview -> src.metadata.magnetUri.ifBlank { "file" }
            is AddSource.TorrentFile -> "file"
        }
        meta.insert(id, name, request.category, clock(), savePath, size, sourceText, request.tags)
        stats.incrementAdded()
        history.record(id, name, size, HistoryEvent.ADDED, savePath)
        eventLog.log(EventType.TORRENT_ADDED, name, savePath, id)
        _notices.tryEmit(ControllerNotice.Added(id, name))
        return id
    }

    /** Fails early with a clear message instead of a disk error from libtorrent minutes later. */
    private suspend fun checkDestination(request: AddRequest, savePath: String) = withContext(Dispatchers.IO) {
        storage.ensureWritable(savePath)
        val metadata = (request.source as? AddSource.Preview)?.metadata ?: return@withContext
        val priorities = request.filePriorities.orEmpty()
        val needed = metadata.files.filter { priorities[it.index] != FilePriority.SKIP }.sumOf { it.size }
        val free = storage.freeSpace(savePath)
        if (needed > 0 && free in 0 until needed) {
            throw GraphiteException(ErrorKind.NO_SPACE, "needs $needed bytes, $free free in $savePath")
        }
    }

    private inline fun forLive(ids: Collection<String>, action: (String) -> Unit) {
        val live = engine.torrents.value
        for (id in ids) if (id in live) action(id)
    }

    override suspend fun pause(ids: Collection<String>) = withContext(Dispatchers.IO) { forLive(ids) { engine.pause(it) } }

    override suspend fun resume(ids: Collection<String>) = withContext(Dispatchers.IO) { forLive(ids) { engine.resume(it) } }

    override suspend fun remove(ids: Collection<String>, deleteFiles: Boolean) {
        for (id in ids) {
            val live = engine.torrents.value[id]
            val row = meta.get(id)
            val name = live?.name ?: row?.name ?: id
            val size = live?.wantedBytes ?: row?.total_size ?: 0
            if (live != null) {
                withContext(Dispatchers.IO) { engine.remove(id, deleteFiles) }
            } else if (deleteFiles && row?.final_location != null) {
                runCatching { storage.deleteRelocated(row.final_location, emptyList()) }
            }
            meta.delete(id)
            history.record(id, name, size, HistoryEvent.REMOVED, live?.savePath ?: row?.final_location.orEmpty())
            eventLog.log(EventType.TORRENT_REMOVED, name, if (deleteFiles) "files deleted" else "", id)
        }
    }

    override suspend fun recheck(id: String) = withContext(Dispatchers.IO) { if (isLive(id)) engine.recheck(id) }

    override suspend fun move(id: String, targetDir: String) = withContext(Dispatchers.IO) {
        val settings = settingsRepository.current()
        engine.move(id, storage.resolveSavePath(targetDir, settings))
    }

    override suspend fun setFilePriorities(id: String, priorities: Map<Int, FilePriority>) =
        withContext(Dispatchers.IO) { if (isLive(id)) engine.setFilePriorities(id, priorities) }

    override suspend fun setSequential(id: String, enabled: Boolean) =
        withContext(Dispatchers.IO) { if (isLive(id)) engine.setSequential(id, enabled) }

    override suspend fun setQueuePosition(id: String, position: Int) =
        withContext(Dispatchers.IO) { if (isLive(id)) engine.setQueuePosition(id, position) }

    override suspend fun setCategory(ids: Collection<String>, category: String?) {
        meta.setCategory(ids, category?.trim()?.ifEmpty { null })
    }

    override suspend fun setTags(id: String, tags: List<String>) = meta.setTags(id, tags)

    override suspend fun addTrackers(id: String, urls: List<String>) = withContext(Dispatchers.IO) {
        val valid = urls.map { it.trim() }.filter { TrackerList.isValid(it) }
        if (valid.isNotEmpty() && isLive(id)) engine.addTrackers(id, valid)
    }

    override suspend fun removeTrackers(id: String, urls: List<String>) =
        withContext(Dispatchers.IO) { if (isLive(id)) engine.removeTrackers(id, urls) }

    override suspend fun reannounce(id: String) = withContext(Dispatchers.IO) { if (isLive(id)) engine.reannounce(id) }

    override suspend fun files(id: String): List<FileEntry> = withContext(Dispatchers.IO) { engine.files(id) }

    override suspend fun peers(id: String): List<PeerEntry> = withContext(Dispatchers.IO) { engine.peers(id) }

    override suspend fun trackers(id: String): List<TrackerEntry> = withContext(Dispatchers.IO) { engine.trackers(id) }

    override suspend fun pieces(id: String): PieceMapSnapshot = withContext(Dispatchers.IO) { engine.pieces(id) }

    override suspend fun pauseAll() = withContext(Dispatchers.IO) {
        engine.torrents.value.values
            .filter { it.state != TorrentState.PAUSED && it.state != TorrentState.COMPLETED }
            .forEach { runCatching { engine.pause(it.id) } }
    }

    override suspend fun resumeAll() = withContext(Dispatchers.IO) {
        engine.torrents.value.values
            .filter { it.state == TorrentState.PAUSED || it.state == TorrentState.ERROR }
            .forEach { runCatching { engine.resume(it.id) } }
    }

    override suspend fun freeSpace(path: String): Long = withContext(Dispatchers.IO) {
        storage.freeSpace(storage.resolveSavePath(path, settingsRepository.current()))
    }

    override suspend fun defaultSavePath(): String = storage.defaultSavePath(settingsRepository.current())

    /** Metadata of a live torrent (used to export .torrent files). */
    suspend fun exportTorrent(id: String): ByteArray? = withContext(Dispatchers.IO) {
        engine.metadata(id)?.torrentDataBase64?.let(Base64Util::decode)
    }

    // ---------------------------------------------------------------------------------------------
    // Background jobs
    // ---------------------------------------------------------------------------------------------

    private suspend fun sampleSpeed() {
        engine.session.collect { s ->
            if (!s.running) return@collect
            val sample = SpeedSample(clock(), s.downloadRate, s.uploadRate)
            _speed.update { (it + sample).takeLast(SPEED_HISTORY_SIZE) }
        }
    }

    private var recordedDown = 0L
    private var recordedUp = 0L
    private var windowPeak = 0L
    private var windowActive = 0L

    private suspend fun recordStats() {
        var tick = 0
        while (scope.isActive) {
            delay(1000)
            val s = engine.session.value
            windowPeak = maxOf(windowPeak, s.downloadRate)
            if (s.downloadRate > 0) windowActive++
            if (++tick % STATS_FLUSH_SECONDS == 0) flushStats()
        }
    }

    private suspend fun flushStats() {
        val s = engine.session.value
        val down = s.sessionDownloaded - recordedDown
        val up = s.sessionUploaded - recordedUp
        if (down > 0 || up > 0 || windowActive > 0) {
            runCatching { stats.addTraffic(down, up, windowActive, windowPeak) }
        }
        recordedDown = s.sessionDownloaded
        recordedUp = s.sessionUploaded
        windowPeak = 0
        windowActive = 0
    }

    private suspend fun monitorInterfaces() {
        while (scope.isActive) {
            delay(INTERFACE_POLL_MS)
            refreshInterfaceBinding(settingsRepository.settings.value, initial = false)
        }
    }

    private suspend fun refreshInterfaceBinding(settings: AppSettings, initial: Boolean) {
        val name = settings.bindInterface
        val before = overrides.value
        val updated = if (name.isBlank()) {
            before.copy(bindAddresses = emptyList(), killSwitchBlocked = false)
        } else {
            val addresses = withContext(Dispatchers.IO) { NetworkInterfaces.addressesOf(name) }
            when {
                addresses.isNotEmpty() -> before.copy(bindAddresses = addresses, killSwitchBlocked = false)
                settings.killSwitch -> before.copy(bindAddresses = emptyList(), killSwitchBlocked = true)
                else -> before.copy(bindAddresses = emptyList(), killSwitchBlocked = false)
            }
        }
        if (updated != before) {
            overrides.value = updated
            if (updated.killSwitchBlocked && !before.killSwitchBlocked) {
                setPauseReason(PauseReason.KILL_SWITCH, true)
                eventLog.log(EventType.KILL_SWITCH_ENGAGED, name, level = LogLevel.WARNING)
            } else if (!updated.killSwitchBlocked && before.killSwitchBlocked) {
                setPauseReason(PauseReason.KILL_SWITCH, false)
                if (!initial) eventLog.log(EventType.KILL_SWITCH_RELEASED, name)
            }
        } else if (!updated.killSwitchBlocked && PauseReason.KILL_SWITCH in pauseReasons.value) {
            setPauseReason(PauseReason.KILL_SWITCH, false)
        }
    }

    private suspend fun updateTrackersPeriodically() {
        while (scope.isActive) {
            val s = settingsRepository.current()
            var wait = TRACKER_CHECK_INTERVAL_MS
            if (s.trackersAutoUpdate && clock() - s.trackersUpdatedAt > TRACKER_UPDATE_INTERVAL_MS) {
                try {
                    updateTrackerList()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No network, the list is blocked by the provider, a TLS problem...: already written to
                    // the event log. The built-in trackers keep working; try again a bit later.
                    wait = TRACKER_RETRY_INTERVAL_MS
                }
            }
            delay(wait)
        }
    }

    /** Downloads the public tracker list now. Returns the number of trackers or throws [GraphiteException]. */
    suspend fun updateTrackerList(): Int {
        val s = settingsRepository.current()
        return try {
            val list = TrackerList.download(s.trackerSources)
            if (list.isEmpty()) throw GraphiteException(ErrorKind.NO_NETWORK, "empty tracker list")
            settingsRepository.update { it.copy(trackerList = list, trackersUpdatedAt = clock()) }
            eventLog.log(EventType.TRACKERS_UPDATED, list.size.toString())
            list.size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = ErrorMapper.fromException(e)
            eventLog.error(EventType.TRACKERS_UPDATE_FAILED, s.trackerSources.joinToString(), error)
            throw GraphiteException(error, e)
        }
    }

    private suspend fun loadIpFilter(enabled: Boolean, path: String) {
        if (!enabled || path.isBlank()) {
            withContext(Dispatchers.IO) { engine.setIpFilter(emptyList()) }
            return
        }
        try {
            val ranges = withContext(Dispatchers.IO) {
                File(path).bufferedReader().useLines { IpFilterParser.parse(it) }
            }
            withContext(Dispatchers.IO) { engine.setIpFilter(ranges) }
            eventLog.log(EventType.IP_FILTER_LOADED, File(path).name, ranges.size.toString())
        } catch (e: Exception) {
            eventLog.error(EventType.IP_FILTER_FAILED, path, ErrorMapper.fromException(e))
        }
    }

    companion object {
        const val METADATA_TIMEOUT_SECONDS = 90
        const val SPEED_HISTORY_SIZE = 600
        const val STATS_FLUSH_SECONDS = 10
        const val INTERFACE_POLL_MS = 3_000L
        const val TRACKER_UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val TRACKER_CHECK_INTERVAL_MS = 60L * 60 * 1000
        const val TRACKER_RETRY_INTERVAL_MS = 15L * 60 * 1000
    }
}
