package com.graphite.torrent.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.common.currentUnitLabels
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.mini_active
import com.graphite.torrent.ui.app.resources.tray_quit
import com.graphite.torrent.ui.designsystem.components.CrystalLogo
import com.graphite.torrent.ui.designsystem.components.GraphiteIconButton
import com.graphite.torrent.ui.designsystem.components.RollingText
import com.graphite.torrent.ui.designsystem.components.SpeedChart
import com.graphite.torrent.ui.designsystem.format.Formatters
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import org.jetbrains.compose.resources.stringResource

/** 320×120 always-on-top window with the total speed. Drag it anywhere; click to open the main window. */
@Composable
fun MiniWindow(env: AppEnvironment, icon: Painter, onClose: () -> Unit, onOpenMain: () -> Unit) {
    val state = rememberWindowState(size = DpSize(320.dp, 120.dp), position = WindowPosition(Alignment.BottomEnd))
    val session by env.controller.session.collectAsState()
    val history by env.controller.speedHistory.collectAsState()
    val settings by env.settings.settings.collectAsState()
    Window(
        onCloseRequest = onClose,
        state = state,
        title = "Graphite",
        icon = icon,
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        transparent = true,
    ) {
        GraphiteTheme(amoled = settings.amoledBlack, reduceMotion = settings.reduceMotion, units = currentUnitLabels()) {
            val colors = GraphiteTheme.colors
            WindowDraggableArea {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.surface1, GraphiteTheme.shapes.card)
                        .border(1.dp, colors.borderStrong, GraphiteTheme.shapes.card)
                        .clickable(onClick = onOpenMain)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CrystalLogo(size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            RollingText(Formatters.speed(session.downloadRate, GraphiteTheme.units), GraphiteTheme.typography.numberLarge)
                            Text(stringResource(Res.string.mini_active, session.activeTorrents), style = GraphiteTheme.typography.caption, color = colors.textSecondary)
                        }
                        GraphiteIconButton(GraphiteIcons.X, stringResource(Res.string.tray_quit), onClose, size = 32.dp, tint = colors.textSecondary)
                    }
                    SpeedChart(
                        history.takeLast(60).map { it.downloadRate },
                        Modifier.align(Alignment.BottomCenter).padding(top = 56.dp),
                        height = 32.dp,
                        interactive = false,
                    )
                }
            }
        }
    }
}
