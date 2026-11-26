package com.graphite.torrent.ui.app.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphite.torrent.core.engine.ErrorMapper
import com.graphite.torrent.core.model.AddRequest
import com.graphite.torrent.core.model.AddSource
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.model.TorrentMetadata
import com.graphite.torrent.core.util.Base64Util
import com.graphite.torrent.core.util.MagnetLink
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.IncomingRequest
import com.graphite.torrent.ui.app.common.FileTree
import com.graphite.torrent.ui.app.common.FileTreeNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AddStep {
    data object Choose : AddStep
    data class Loading(val label: String, val magnet: String?) : AddStep
    data class Preview(val metadata: TorrentMetadata, val tree: List<FileTreeNode>) : AddStep
    data class Failed(val error: TorrentError, val retry: (() -> Unit)?) : AddStep
    data object Done : AddStep
}

data class AddOptions(
    val savePath: String = "",
    val displayPath: String = "",
    val freeSpace: Long = -1,
    val priorities: Map<Int, FilePriority> = emptyMap(),
    val expanded: Set<String> = emptySet(),
    val category: String = "",
    val sequential: Boolean = false,
    val startPaused: Boolean = false,
)

class AddViewModel(private val env: AppEnvironment) : ViewModel() {
    private val _step = MutableStateFlow<AddStep>(AddStep.Choose)
    val step: StateFlow<AddStep> = _step.asStateFlow()

    private val _options = MutableStateFlow(AddOptions())
    val options: StateFlow<AddOptions> = _options.asStateFlow()

    private val _link = MutableStateFlow("")
    val link: StateFlow<String> = _link.asStateFlow()

    /** A magnet link found in the clipboard when the sheet opened. */
    private val _clipboardMagnet = MutableStateFlow<String?>(null)
    val clipboardMagnet: StateFlow<String?> = _clipboardMagnet.asStateFlow()

    val canScanQr: Boolean get() = env.platform.canScanQr
    val categories = env.meta.categories

    private var job: Job? = null

    init {
        viewModelScope.launch(env.errorHandler) {
            val settings = env.settings.current()
            val path = env.controller.defaultSavePath()
            _options.update {
                it.copy(
                    savePath = path,
                    displayPath = env.platform.displayPath(settings.androidTreeUri.ifBlank { path }),
                    sequential = settings.sequentialByDefault,
                    startPaused = settings.startPaused,
                )
            }
            refreshFreeSpace()
        }
    }

    fun reset() {
        job?.cancel()
        _step.value = AddStep.Choose
        _link.value = ""
        _clipboardMagnet.value = env.platform.readClipboardText()?.let(MagnetLink::findInText)
    }

    fun setLink(text: String) {
        _link.value = text
    }

    fun submitLink() {
        val text = _link.value.trim()
        val magnet = MagnetLink.findInText(text)
        if (magnet == null) {
            _step.value = AddStep.Failed(TorrentError(ErrorKind.INVALID_TORRENT, text.take(80)), null)
            return
        }
        openMagnet(magnet)
    }

    fun handle(request: IncomingRequest) {
        when (request) {
            is IncomingRequest.Magnet -> openMagnet(request.uri)
            is IncomingRequest.TorrentFile -> openTorrentFile(request.name, request.bytes)
            is IncomingRequest.Text -> MagnetLink.findInText(request.text)?.let(::openMagnet)
                ?: run { _step.value = AddStep.Failed(TorrentError(ErrorKind.INVALID_TORRENT, request.text.take(80)), null) }
        }
    }

    fun pickFile() {
        viewModelScope.launch(env.errorHandler) {
            val files = runCatching { env.platform.pickTorrentFiles() }.getOrDefault(emptyList())
            when {
                files.isEmpty() -> Unit
                files.size == 1 -> openTorrentFile(files[0].name, files[0].bytes)
                else -> addManyFiles(files.map { it.bytes })
            }
        }
    }

    fun scanQr() {
        viewModelScope.launch(env.errorHandler) {
            val text = runCatching { env.platform.scanQr() }.getOrNull() ?: return@launch
            val magnet = MagnetLink.findInText(text)
            if (magnet != null) openMagnet(magnet) else _step.value = AddStep.Failed(TorrentError(ErrorKind.INVALID_TORRENT, text.take(80)), null)
        }
    }

    fun openMagnet(uri: String) {
        job?.cancel()
        val name = MagnetLink.parse(uri)?.displayName ?: uri.take(60)
        _step.value = AddStep.Loading(name, uri)
        job = viewModelScope.launch(env.errorHandler) {
            try {
                showPreview(env.controller.previewMagnet(uri))
            } catch (e: GraphiteException) {
                _step.value = AddStep.Failed(e.error) { openMagnet(uri) }
            } catch (e: Exception) {
                _step.value = AddStep.Failed(ErrorMapper.fromException(e)) { openMagnet(uri) }
            }
        }
    }

    /** Adds the magnet immediately without waiting for metadata. */
    fun addWithoutPreview() {
        val magnet = (_step.value as? AddStep.Loading)?.magnet ?: return
        job?.cancel()
        add(AddSource.Magnet(magnet))
    }

    private fun openTorrentFile(name: String, bytes: ByteArray) {
        job?.cancel()
        _step.value = AddStep.Loading(name, null)
        job = viewModelScope.launch(env.errorHandler) {
            try {
                showPreview(env.controller.previewTorrentFile(bytes))
            } catch (e: GraphiteException) {
                _step.value = AddStep.Failed(e.error, null)
            } catch (e: Exception) {
                _step.value = AddStep.Failed(ErrorMapper.fromException(e), null)
            }
        }
    }

    private fun addManyFiles(files: List<ByteArray>) {
        viewModelScope.launch(env.errorHandler) {
            var lastError: TorrentError? = null
            for (bytes in files) {
                try {
                    env.controller.add(requestFor(AddSource.TorrentFile(Base64Util.encode(bytes)), emptyMap()))
                } catch (e: GraphiteException) {
                    lastError = e.error
                }
            }
            _step.value = lastError?.let { AddStep.Failed(it, null) } ?: AddStep.Done
        }
    }

    private fun showPreview(metadata: TorrentMetadata) {
        val entries = metadata.files.map { FileEntry(it.index, it.path, it.size, 0, FilePriority.NORMAL) }
        val tree = FileTree.build(entries)
        _options.update {
            it.copy(
                priorities = metadata.files.associate { f -> f.index to FilePriority.NORMAL },
                expanded = if (metadata.files.size <= 30) FileTree.allFolders(tree) else emptySet(),
            )
        }
        _step.value = AddStep.Preview(metadata, tree)
    }

    fun toggle(node: FileTreeNode) {
        _options.update { it.copy(priorities = it.priorities + FileTree.toggle(node, it.priorities)) }
    }

    fun toggleExpanded(path: String) {
        _options.update { it.copy(expanded = if (path in it.expanded) it.expanded - path else it.expanded + path) }
    }

    fun selectAll(select: Boolean) {
        _options.update { o -> o.copy(priorities = o.priorities.mapValues { if (select) FilePriority.NORMAL else FilePriority.SKIP }) }
    }

    fun setCategory(c: String) = _options.update { it.copy(category = c) }
    fun setSequential(v: Boolean) = _options.update { it.copy(sequential = v) }
    fun setStartPaused(v: Boolean) = _options.update { it.copy(startPaused = v) }

    fun chooseFolder() {
        viewModelScope.launch(env.errorHandler) {
            val folder = env.platform.pickFolder() ?: return@launch
            _options.update { it.copy(savePath = folder, displayPath = env.platform.displayPath(folder)) }
            refreshFreeSpace()
        }
    }

    private suspend fun refreshFreeSpace() {
        val free = runCatching { env.controller.freeSpace(_options.value.savePath) }.getOrDefault(-1L)
        _options.update { it.copy(freeSpace = free) }
    }

    fun confirm() {
        val preview = _step.value as? AddStep.Preview ?: return
        add(AddSource.Preview(preview.metadata))
    }

    private fun requestFor(source: AddSource, priorities: Map<Int, FilePriority>): AddRequest {
        val o = _options.value
        return AddRequest(
            source = source,
            savePath = o.savePath.ifBlank { null },
            filePriorities = priorities.takeIf { p -> p.values.any { it != FilePriority.NORMAL } },
            sequential = o.sequential,
            startPaused = o.startPaused,
            category = o.category.trim().ifEmpty { null },
        )
    }

    private fun add(source: AddSource) {
        viewModelScope.launch(env.errorHandler) {
            try {
                env.controller.add(requestFor(source, _options.value.priorities))
                _step.value = AddStep.Done
            } catch (e: GraphiteException) {
                _step.value = AddStep.Failed(e.error, null)
            } catch (e: Exception) {
                _step.value = AddStep.Failed(ErrorMapper.fromException(e), null)
            }
        }
    }
}
