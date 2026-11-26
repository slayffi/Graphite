package com.graphite.torrent.android

import com.graphite.torrent.R
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.graphite.torrent.ui.designsystem.format.Formatters
import com.graphite.torrent.ui.designsystem.theme.UnitLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Quick settings tile: shows the download speed, tap pauses or resumes everything. */
class GraphiteTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        val app = application as GraphiteApplication
        if (!app.ready) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch {
                app.services.controller.session.collect { render() }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val app = application as GraphiteApplication
        if (!app.ready) return
        app.services.scope.launch {
            toggleAll(this@GraphiteTileService)
            CoroutineScope(Dispatchers.Main).launch { render() }
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val app = application as GraphiteApplication
        if (!app.ready) return
        val summary = WidgetSummary.of(app.services.controller.torrents.value, app.services.controller.session.value)
        val units = if (Locale.getDefault().language == "ru") UnitLabels.Russian else UnitLabels.English
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_graphite)
        tile.label = getString(R.string.app_name)
        tile.state = if (summary.downloading) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitle = when {
            summary.downloading -> "↓ " + Formatters.speed(summary.downloadRate, units)
            summary.total == 0 -> getString(R.string.widget_empty)
            else -> getString(R.string.tile_paused)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = getString(R.string.app_name) + ", " + subtitle
        tile.updateTile()
    }
}
