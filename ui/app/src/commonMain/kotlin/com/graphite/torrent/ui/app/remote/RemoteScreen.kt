package com.graphite.torrent.ui.app.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.RemoteFeature
import com.graphite.torrent.ui.app.common.adviceRes
import com.graphite.torrent.ui.app.common.titleRes
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.ButtonKind
import com.graphite.torrent.ui.designsystem.components.GraphiteButton
import com.graphite.torrent.ui.designsystem.components.GraphiteCard
import com.graphite.torrent.ui.designsystem.components.GraphiteIconButton
import com.graphite.torrent.ui.designsystem.components.InfoRow
import com.graphite.torrent.ui.designsystem.components.SwitchSettingRow
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** QR code rendered with ZXing: dark modules on a light square, as every scanner expects. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 240.dp, description: String = "") {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Canvas(modifier.size(size).clip(GraphiteTheme.shapes.control).semantics { contentDescription = description }) {
        drawRect(Color(0xFFEDEDED))
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(Color(0xFF0B0B0C), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
fun RemoteScreen(env: AppEnvironment, remote: RemoteFeature, onBack: () -> Unit, onShowMessage: (String) -> Unit) {
    val spacing = GraphiteTheme.spacing
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            GraphiteIconButton(GraphiteIcons.ArrowLeft, stringResource(Res.string.back), onBack)
            Text(stringResource(Res.string.remote_title), style = GraphiteTheme.typography.screenTitle, modifier = Modifier.weight(1f).padding(start = spacing.xs))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            if (remote.isServer) DesktopRemote(env, remote) else PhoneRemote(env, remote, onShowMessage)
        }
    }
}

@Composable
private fun DesktopRemote(env: AppEnvironment, remote: RemoteFeature) {
    val settings by env.settings.settings.collectAsStateWithLifecycle()
    val payload by remote.pairingPayload.collectAsStateWithLifecycle()
    val clients by remote.connectedClients.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    GraphiteCard(Modifier.widthIn(max = 640.dp), contentPadding = GraphiteTheme.spacing.xxs) {
        SwitchSettingRow(
            stringResource(Res.string.remote_allow),
            stringResource(Res.string.remote_desc_desktop),
            settings.remoteServerEnabled,
            { v -> scope.launch { env.settings.update { it.copy(remoteServerEnabled = v) } } },
            icon = GraphiteIcons.Smartphone,
        )
    }
    val code = payload
    if (settings.remoteServerEnabled && code != null) {
        GraphiteCard(Modifier.widthIn(max = 640.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QrCode(code, size = 220.dp, description = stringResource(Res.string.remote_qr))
                Spacer(Modifier.size(GraphiteTheme.spacing.lg))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GraphiteTheme.spacing.xs)) {
                    Text(stringResource(Res.string.remote_scan_steps), style = GraphiteTheme.typography.body)
                    Text(stringResource(Res.string.remote_security), style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
                    InfoRow(stringResource(Res.string.remote_port), settings.remotePort.toString())
                    InfoRow(stringResource(Res.string.remote_clients), clients.toString())
                    GraphiteButton(stringResource(Res.string.remote_new_key), { scope.launch { remote.regenerateKey() } }, kind = ButtonKind.SECONDARY, icon = GraphiteIcons.RefreshCw)
                }
            }
        }
    }
}

@Composable
private fun PhoneRemote(env: AppEnvironment, remote: RemoteFeature, onShowMessage: (String) -> Unit) {
    val settings by env.settings.settings.collectAsStateWithLifecycle()
    val connected by remote.connected.collectAsStateWithLifecycle()
    val name by remote.remoteName.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pairing = settings.remotePairing
    suspend fun fail(e: GraphiteException) = onShowMessage(getString(e.error.kind.titleRes()) + ". " + getString(e.error.kind.adviceRes()))

    if (pairing == null) {
        GraphiteCard {
            Text(stringResource(Res.string.remote_not_paired), style = GraphiteTheme.typography.body)
            Text(stringResource(Res.string.remote_pair_hint), style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
            Spacer(Modifier.height(GraphiteTheme.spacing.sm))
            GraphiteButton(stringResource(Res.string.remote_scan), {
                scope.launch {
                    val text = env.platform.scanQr() ?: return@launch
                    try {
                        remote.pair(text)
                    } catch (e: GraphiteException) {
                        fail(e)
                    }
                }
            }, icon = GraphiteIcons.ScanLine)
        }
    } else {
        GraphiteCard(contentPadding = GraphiteTheme.spacing.xxs) {
            SwitchSettingRow(
                stringResource(Res.string.remote_control_pc, name ?: pairing.name),
                stringResource(if (connected) Res.string.remote_connected else Res.string.remote_disconnected),
                settings.remoteMode,
                { v ->
                    scope.launch {
                        try {
                            if (v) remote.pair(com.graphite.torrent.data.remote.PairingCode.format(pairing.name, pairing.hosts, pairing.port,
                                com.graphite.torrent.core.util.Base64Util.decodeUrl(pairing.keyBase64))) else remote.disconnect()
                        } catch (e: GraphiteException) {
                            fail(e)
                        }
                    }
                },
                icon = GraphiteIcons.Monitor,
            )
        }
        GraphiteCard {
            InfoRow(stringResource(Res.string.remote_address), pairing.hosts.joinToString(", ") + ":" + pairing.port)
            Spacer(Modifier.height(GraphiteTheme.spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(GraphiteTheme.spacing.xs)) {
                GraphiteButton(stringResource(Res.string.remote_scan), {
                    scope.launch {
                        val text = env.platform.scanQr() ?: return@launch
                        try {
                            remote.pair(text)
                        } catch (e: GraphiteException) {
                            fail(e)
                        }
                    }
                }, kind = ButtonKind.SECONDARY, icon = GraphiteIcons.ScanLine)
                GraphiteButton(stringResource(Res.string.remote_forget), { scope.launch { remote.forget() } }, kind = ButtonKind.DANGER)
            }
        }
    }
    Box(Modifier.fillMaxWidth().background(Color.Transparent))
}
