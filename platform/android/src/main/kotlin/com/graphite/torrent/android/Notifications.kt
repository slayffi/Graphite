package com.graphite.torrent.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.graphite.torrent.R
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.ui.designsystem.format.Formatters
import com.graphite.torrent.ui.designsystem.theme.UnitLabels
import java.util.Locale

object Notifications {
    const val CHANNEL_DOWNLOADS = "downloads"
    const val CHANNEL_COMPLETED = "completed"
    const val ID_SERVICE = 1
    private const val ID_COMPLETED_BASE = 1000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_downloads_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETED, context.getString(R.string.channel_completed), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_completed_desc)
            },
        )
    }

    private fun units(): UnitLabels = if (Locale.getDefault().language == "ru") UnitLabels.Russian else UnitLabels.English

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceAction(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getService(
        context, code,
        Intent(context, TorrentService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Ongoing notification with overall progress, speed and Pause / Resume / Exit buttons. */
    fun progress(context: Context, session: SessionSnapshot, torrents: List<TorrentItem>): android.app.Notification {
        val u = units()
        val active = torrents.filter { it.state == TorrentState.DOWNLOADING || it.state == TorrentState.METADATA || it.state == TorrentState.CHECKING || it.state == TorrentState.QUEUED }
        val wanted = active.sumOf { it.wantedBytes }
        val done = active.sumOf { it.doneBytes }
        val progress = if (wanted > 0) (done * 1000 / wanted).toInt() else 0
        val paused = session.pauseReasons.isNotEmpty()
        val title = when {
            paused -> context.getString(R.string.notif_paused, session.pauseReasons.joinToString { it.name.lowercase() })
            active.isEmpty() -> context.getString(R.string.notif_idle)
            else -> context.getString(R.string.notif_downloading, active.size)
        }
        val text = buildString {
            append(Formatters.speed(session.downloadRate, u))
            if (wanted > 0) {
                append(" · ")
                append(Formatters.percent(done.toFloat() / wanted, u))
            }
            active.firstOrNull()?.let { append(" · ").append(it.name) }
        }
        val anyPaused = torrents.any { it.state == TorrentState.PAUSED }
        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_graphite)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, progress, active.isNotEmpty() && wanted == 0L)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_pause_all), serviceAction(context, TorrentService.ACTION_PAUSE_ALL, 1))
            .apply { if (anyPaused) addAction(0, context.getString(R.string.action_resume_all), serviceAction(context, TorrentService.ACTION_RESUME_ALL, 2)) }
            .addAction(0, context.getString(R.string.action_exit), serviceAction(context, TorrentService.ACTION_EXIT, 3))
            .build()
    }

    fun completed(context: Context, id: String, name: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(R.drawable.ic_stat_graphite)
            .setContentTitle(context.getString(R.string.notif_completed))
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_COMPLETED_BASE + (id.hashCode() and 0xFFFF), notification) }
    }
}
