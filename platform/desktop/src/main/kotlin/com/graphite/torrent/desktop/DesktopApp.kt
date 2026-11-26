package com.graphite.torrent.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.graphite.torrent.core.util.MagnetLink
import com.graphite.torrent.data.GraphiteServices
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.GraphiteApp
import com.graphite.torrent.ui.app.IncomingRequest
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.drop_here
import com.graphite.torrent.ui.app.resources.pause_all
import com.graphite.torrent.ui.app.resources.resume_all
import com.graphite.torrent.ui.app.resources.tray_add
import com.graphite.torrent.ui.app.resources.tray_mini
import com.graphite.torrent.ui.app.resources.tray_quit
import com.graphite.torrent.ui.app.resources.tray_show
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteColors
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.io.File
import com.graphite.torrent.ui.designsystem.resources.Res as DsRes
import com.graphite.torrent.ui.designsystem.resources.app_icon

/** Compose Desktop application: main window, tray, mini window, drag and drop and shortcuts. */
fun runDesktopApp(
    env: AppEnvironment,
    services: GraphiteServices,
    desktop: DesktopController,
    onFrame: (Frame) -> Unit,
    onExit: () -> Unit,
    onContentShown: () -> Unit = {},
) = application(exitProcessOnExit = true) {
    val settings by env.settings.settings.collectAsState()
    val icon = painterResource(DsRes.drawable.app_icon)
    val trayState = rememberTrayState()
    val windowVisible by desktop.windowVisible.collectAsState()
    val miniVisible by desktop.miniVisible.collectAsState()

    fun quit() {
        desktop.stop()
        services.stopAutomation()
        services.remoteServer.stop()
        services.streams.stop()
        runBlocking { services.controller.stop() }
        onExit()
        exitApplication()
    }

    LaunchedEffect(Unit) {
        if (env.settings.current().startMinimized) desktop.hideWindow()
        desktop.start(env, trayState) { quit() }
    }

    Tray(
        icon = icon,
        state = trayState,
        tooltip = "Graphite",
        onAction = { desktop.showWindow() },
        menu = {
            Item(stringResource(Res.string.tray_show), onClick = { desktop.showWindow() })
            Item(stringResource(Res.string.tray_add), onClick = {
                desktop.showWindow()
                env.openAddSheet.value = true
            })
            Item(stringResource(Res.string.tray_mini), onClick = { desktop.toggleMiniWindow() })
            Separator()
            Item(stringResource(Res.string.pause_all), onClick = { services.scope.launch { env.controller.pauseAll() } })
            Item(stringResource(Res.string.resume_all), onClick = { services.scope.launch { env.controller.resumeAll() } })
            Separator()
            Item(stringResource(Res.string.tray_quit), onClick = { quit() })
        },
    )

    val windowState = rememberWindowState(width = 1240.dp, height = 820.dp, position = WindowPosition(Alignment.Center))
    Window(
        onCloseRequest = { if (settings.minimizeToTray) desktop.hideWindow() else quit() },
        visible = windowVisible,
        state = windowState,
        title = "Graphite",
        icon = icon,
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@Window false
            when (event.key) {
                Key.O -> {
                    env.openAddSheet.value = true
                    true
                }
                Key.V -> {
                    val magnet = env.platform.readClipboardText()?.let(MagnetLink::findInText) ?: return@Window false
                    env.submit(IncomingRequest.Magnet(magnet))
                    true
                }
                else -> false
            }
        },
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(520, 600)
            onFrame(window)
            desktop.attachWindow(window)
        }
        LaunchedEffect(window, settings.amoledBlack) {
            val colors = if (settings.amoledBlack) GraphiteColors.Amoled else GraphiteColors.Dark
            WindowsTheme.apply(window, colors.bg.toArgb(), colors.textPrimary.toArgb(), colors.border.toArgb())
        }
        DropArea(env) {
            GraphiteApp(env, onContentShown = onContentShown)
        }
    }

    if (miniVisible) {
        MiniWindow(env, icon, onClose = { desktop.toggleMiniWindow() }, onOpenMain = { desktop.showWindow() })
    }
}

/** Full-window drop zone for .torrent files and magnet links. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DropArea(env: AppEnvironment, content: @Composable () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                dragging = true
            }

            override fun onExited(event: DragAndDropEvent) {
                dragging = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                dragging = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                val transferable = event.awtTransferable
                var handled = false
                if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @Suppress("UNCHECKED_CAST")
                    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File> ?: emptyList()
                    files.filter { it.isFile && it.name.lowercase().endsWith(".torrent") }.forEach {
                        env.submit(IncomingRequest.TorrentFile(it.name, it.readBytes()))
                        handled = true
                    }
                }
                if (!handled && transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
                    text?.let(MagnetLink::findInText)?.let {
                        env.submit(IncomingRequest.Magnet(it))
                        handled = true
                    }
                }
                return handled
            }
        }
    }
    Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)) {
        content()
        AnimatedVisibility(dragging, enter = fadeIn(), exit = fadeOut()) {
            GraphiteTheme {
                val colors = GraphiteTheme.colors
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.bg.copy(alpha = 0.92f))
                        .padding(24.dp)
                        .border(1.dp, colors.accent, GraphiteTheme.shapes.card),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(GraphiteIcons.Download, null, tint = colors.accentBright, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(Res.string.drop_here), style = GraphiteTheme.typography.itemTitle, color = colors.textPrimary)
                    }
                }
            }
        }
    }
}
