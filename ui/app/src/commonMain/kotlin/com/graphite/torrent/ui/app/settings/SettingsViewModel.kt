package com.graphite.torrent.ui.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.net.NetInterface
import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.core.trackers.TrackerList
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.designsystem.platform.PlatformKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SettingsMessage {
    data class TrackersUpdated(val count: Int) : SettingsMessage
    data class Failed(val error: com.graphite.torrent.core.model.TorrentError) : SettingsMessage
    data class Exported(val ok: Boolean) : SettingsMessage
    data class Imported(val count: Int) : SettingsMessage
}

class SettingsViewModel(private val env: AppEnvironment) : ViewModel() {
    val settings: StateFlow<AppSettings> = env.settings.settings
    val session: StateFlow<SessionSnapshot> = env.controller.session
    val platformKind: PlatformKind = env.platform.kind
    val appVersion: String = env.platform.appVersion
    val supportsAllFilesAccess = env.platform.supportsAllFilesAccess
    val features = env.features

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _interfaces = MutableStateFlow<List<NetInterface>>(emptyList())
    val interfaces: StateFlow<List<NetInterface>> = _interfaces.asStateFlow()

    private val _allFilesAccess = MutableStateFlow(env.platform.hasAllFilesAccess())
    val allFilesAccess: StateFlow<Boolean> = _allFilesAccess.asStateFlow()

    private val _messages = MutableSharedFlow<SettingsMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<SettingsMessage> = _messages.asSharedFlow()

    private val _defaultFolder = MutableStateFlow("")
    val defaultFolder: StateFlow<String> = _defaultFolder.asStateFlow()

    init {
        viewModelScope.launch {
            settings.collect { _defaultFolder.value = env.platform.displayPath(env.controller.defaultSavePath()) }
        }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun setQuery(q: String) {
        _query.value = q
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { env.settings.update(transform) }
    }

    fun refreshInterfaces() {
        viewModelScope.launch {
            _interfaces.value = withContext(Dispatchers.IO) { env.platform.networkInterfaces() }
        }
    }

    fun refreshAllFilesAccess() {
        _allFilesAccess.value = env.platform.hasAllFilesAccess()
    }

    fun requestAllFilesAccess() = env.platform.requestAllFilesAccess()

    fun pickDefaultFolder() {
        viewModelScope.launch {
            val folder = env.platform.pickFolder() ?: return@launch
            if (folder.startsWith("content://")) {
                env.settings.update { it.copy(androidTreeUri = folder) }
            } else {
                env.settings.update { it.copy(defaultSavePath = folder, androidTreeUri = "") }
            }
        }
    }

    fun pickMoveCompletedFolder() {
        viewModelScope.launch {
            val folder = env.platform.pickFolder() ?: return@launch
            env.settings.update { it.copy(moveCompletedTo = folder) }
        }
    }

    fun pickWatchFolder() {
        viewModelScope.launch {
            val folder = env.platform.pickFolder() ?: return@launch
            env.settings.update { it.copy(watchFolderPath = folder, watchFolderEnabled = true) }
        }
    }

    fun pickIpFilter() {
        viewModelScope.launch {
            val path = env.platform.pickFilePath(listOf("text/plain", "application/octet-stream", "*/*")) ?: return@launch
            env.settings.update { it.copy(ipFilterPath = path, ipFilterEnabled = true) }
        }
    }

    fun displayPath(path: String): String = if (path.isBlank()) "" else env.platform.displayPath(path)

    fun updateTrackersNow() {
        viewModelScope.launch {
            _busy.value = true
            try {
                _messages.tryEmit(SettingsMessage.TrackersUpdated(env.local.updateTrackerList()))
            } catch (e: GraphiteException) {
                _messages.tryEmit(SettingsMessage.Failed(e.error))
            } finally {
                _busy.value = false
            }
        }
    }

    fun setTrackerListText(text: String) = update { it.copy(trackerList = TrackerList.parse(text)) }

    fun setTrackerSourcesText(text: String) = update {
        it.copy(trackerSources = text.lines().map { l -> l.trim() }.filter { l -> l.startsWith("http") })
    }

    fun exportBackup() {
        val backup = env.features.backup ?: return
        viewModelScope.launch {
            val ok = runCatching {
                env.platform.saveFile("graphite-backup.json", "application/json", backup.export())
            }.getOrDefault(false)
            _messages.tryEmit(SettingsMessage.Exported(ok))
        }
    }

    fun importBackup() {
        val backup = env.features.backup ?: return
        viewModelScope.launch {
            val file = env.platform.pickFile(listOf("application/json", "*/*")) ?: return@launch
            try {
                _messages.tryEmit(SettingsMessage.Imported(backup.import(file.bytes)))
            } catch (e: GraphiteException) {
                _messages.tryEmit(SettingsMessage.Failed(e.error))
            } catch (e: Exception) {
                _messages.tryEmit(SettingsMessage.Failed(com.graphite.torrent.core.engine.ErrorMapper.fromException(e)))
            }
        }
    }

    fun openAppSettings() = env.platform.openAppSettings()
    fun requestNotifications() = env.platform.requestNotificationPermission()
    fun openMiniWindow() = env.features.desktop?.openMiniWindow()
}
