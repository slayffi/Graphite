package com.graphite.torrent.desktop

import com.graphite.torrent.data.GraphiteServices
import com.graphite.torrent.ui.app.PlayerFactory
import com.graphite.torrent.ui.app.RemoteFeature
import com.graphite.torrent.ui.app.StreamingFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class DesktopStreaming(private val services: GraphiteServices) : StreamingFeature {
    override val player: PlayerFactory = VlcPlayerFactory()

    override suspend fun streamUrl(torrentId: String, fileIndex: Int): String = withContext(Dispatchers.IO) {
        val name = services.engine.files(torrentId).firstOrNull { it.index == fileIndex }?.name ?: "file"
        services.streams.url(torrentId, fileIndex, name)
    }
}

/** The desktop is the server side of remote control. */
class DesktopRemote(private val services: GraphiteServices) : RemoteFeature {
    override val isServer = true
    override val pairingPayload: StateFlow<String?> = services.remoteServer.pairingPayload
    override val connectedClients: StateFlow<Int> = services.remoteServer.connectedClients
    override suspend fun regenerateKey() = services.remoteServer.regenerateKey()
    override suspend fun pair(payload: String) = Unit
    override suspend fun disconnect() = Unit
    override suspend fun forget() = Unit
    override val connected: StateFlow<Boolean> = MutableStateFlow(false)
    override val remoteName: StateFlow<String?> = MutableStateFlow(null)
}
