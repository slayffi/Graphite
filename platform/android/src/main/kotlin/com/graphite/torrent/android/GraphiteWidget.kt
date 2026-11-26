package com.graphite.torrent.android

import android.content.Context
import com.graphite.torrent.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.ui.designsystem.format.Formatters
import com.graphite.torrent.ui.designsystem.theme.UnitLabels
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** What the widget and the quick settings tile show. */
data class WidgetSummary(
    val active: Int = 0,
    val total: Int = 0,
    val downloadRate: Long = 0,
    /** Combined progress of unfinished downloads, 0..1. */
    val progress: Float = 0f,
    val hasPaused: Boolean = false,
) {
    val downloading: Boolean get() = active > 0

    /** Rounded to what is displayed, so a change in the last digits of the speed does not redraw the widget. */
    fun rounded(): WidgetSummary = copy(
        downloadRate = if (downloadRate < 1024) 0 else {
            var step = 1L
            while (downloadRate / step >= 100) step *= 10
            downloadRate / step * step
        },
        progress = (progress * 100).toInt() / 100f,
    )

    companion object {
        fun of(torrents: List<TorrentItem>, session: SessionSnapshot): WidgetSummary {
            val unfinished = torrents.filter { it.state != TorrentState.COMPLETED }
            val wanted = unfinished.sumOf { it.wantedBytes }
            val done = unfinished.sumOf { it.doneBytes.coerceAtMost(it.wantedBytes) }
            return WidgetSummary(
                active = torrents.count { it.state in TorrentService.ACTIVE },
                total = torrents.size,
                downloadRate = session.downloadRate,
                progress = if (wanted > 0) (done.toDouble() / wanted).toFloat().coerceIn(0f, 1f) else if (unfinished.isEmpty()) 1f else 0f,
                hasPaused = torrents.any { it.state == TorrentState.PAUSED },
            )
        }
    }
}

/** Home screen widget: overall speed, active downloads and a pause/resume button. */
class GraphiteWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GraphiteApplication
        val initial = if (app.ready) WidgetSummary.of(app.services.controller.torrents.value, app.services.controller.session.value) else WidgetSummary()
        provideContent {
            WidgetContent(context, latest.value ?: initial)
        }
    }

    companion object {
        /** Last summary pushed by the application; recomposes running widget sessions. */
        private val latest = MutableStateFlow<WidgetSummary?>(null)

        suspend fun push(context: Context, summary: WidgetSummary) {
            val changed = latest.value != summary
            latest.value = summary
            if (!changed) return
            val ids = runCatching { GlanceAppWidgetManager(context).getGlanceIds(GraphiteWidget::class.java) }.getOrDefault(emptyList())
            if (ids.isNotEmpty()) runCatching { GraphiteWidget().updateAll(context) }
        }
    }
}

private val WidgetBg = Color(0xFF141416)
private val WidgetSurface = Color(0xFF26262A)
private val WidgetText = Color(0xFFEDEDED)
private val WidgetSecondary = Color(0xFF9A9AA0)
private val WidgetAccent = Color(0xFFC8C8CC)
private val WidgetTrack = Color(0xFF2A2A2E)

@Composable
private fun WidgetContent(context: Context, s: WidgetSummary) {
    val ru = Locale.getDefault().language == "ru"
    val units = if (ru) UnitLabels.Russian else UnitLabels.English
    val open = actionStartActivity<MainActivity>()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBg)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(open),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Image(
                provider = ImageProvider(R.drawable.ic_stat_graphite),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorProvider(WidgetAccent)),
                modifier = GlanceModifier.size(18.dp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = context.getString(R.string.app_name),
                style = TextStyle(color = ColorProvider(WidgetText), fontSize = 14.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = "↓ " + Formatters.speed(s.downloadRate, units),
                style = TextStyle(color = ColorProvider(WidgetText), fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        Text(
            text = when {
                s.total == 0 -> context.getString(R.string.widget_empty)
                s.downloading -> context.resources.getQuantityString(R.plurals.widget_active, s.active, s.active) +
                    " · " + Formatters.percent(s.progress, units)
                else -> context.getString(R.string.widget_idle, s.total)
            },
            style = TextStyle(color = ColorProvider(WidgetSecondary), fontSize = 12.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(8.dp))
        LinearProgressIndicator(
            progress = s.progress,
            color = ColorProvider(WidgetAccent),
            backgroundColor = ColorProvider(WidgetTrack),
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
        )
        Spacer(GlanceModifier.defaultWeight())
        if (s.total > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(WidgetSurface)
                    .cornerRadius(20.dp)
                    .clickable(actionRunCallback<ToggleAllAction>()),
            ) {
                Text(
                    text = context.getString(if (s.downloading) R.string.action_pause_all else R.string.action_resume_all),
                    style = TextStyle(color = ColorProvider(WidgetText), fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

/** Pauses everything when something downloads, otherwise resumes. */
class ToggleAllAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        toggleAll(context)
    }
}

internal suspend fun toggleAll(context: Context) {
    val app = context.applicationContext as GraphiteApplication
    if (!app.ready) return
    app.ensureRunning()
    val controller = app.services.controller
    if (controller.torrents.value.any { it.state in TorrentService.ACTIVE }) {
        controller.pauseAll()
    } else {
        controller.resumeAll()
        TorrentService.start(context)
    }
}

class GraphiteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GraphiteWidget()
}
