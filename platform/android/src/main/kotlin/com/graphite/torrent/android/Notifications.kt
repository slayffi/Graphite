package com.graphite.torrent.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.graphite.torrent.R
import com.graphite.torrent.core.model.PauseReason
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

    // Created once: building a PendingIntent is a call into the system, and the notification is refreshed often.
    private var openAppIntent: PendingIntent? = null
    private val serviceIntents = HashMap<String, PendingIntent>()

    private fun openApp(context: Context): PendingIntent = openAppIntent ?: PendingIntent.getActivity(
        context.applicationContext, 0,
        Intent(context.applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    ).also { openAppIntent = it }

    private fun serviceAction(context: Context, action: String, code: Int): PendingIntent = serviceIntents.getOrPut(action) {
        PendingIntent.getService(
            context.applicationContext, code,
            Intent(context.applicationContext, TorrentService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Everything the ongoing notification shows. Equal content means there is nothing to post again. */
    data class ProgressContent(
        val title: String,
        val text: String,
        val progress: Int,
        val indeterminate: Boolean,
        val canResume: Boolean,
    )

    private fun reasonText(context: Context, reason: PauseReason): String = context.getString(
        when (reason) {
            PauseReason.USER -> R.string.reason_user
            PauseReason.SCHEDULE -> R.string.reason_schedule
            PauseReason.NO_WIFI -> R.string.reason_no_wifi
            PauseReason.NOT_CHARGING -> R.string.reason_not_charging
            PauseReason.LOW_BATTERY -> R.string.reason_low_battery
            PauseReason.KILL_SWITCH -> R.string.reason_kill_switch
            PauseReason.NO_NETWORK -> R.string.reason_no_network
        },
    )

    fun progressContent(context: Context, session: SessionSnapshot, torrents: List<TorrentItem>): ProgressContent {
        val u = units()
        val moving = torrents.filter { it.state == TorrentState.MOVING }
        val active = torrents.filter { it.state == TorrentState.DOWNLOADING || it.state == TorrentState.METADATA || it.state == TorrentState.CHECKING || it.state == TorrentState.QUEUED }
        val wanted = active.sumOf { it.wantedBytes }
        val done = active.sumOf { it.doneBytes }
        val paused = session.pauseReasons.isNotEmpty()
        val title = when {
            moving.isNotEmpty() -> context.getString(R.string.notif_moving, moving.first().name)
            paused -> context.getString(R.string.notif_paused, session.pauseReasons.joinToString { reasonText(context, it) })
            active.isEmpty() -> context.getString(R.string.notif_idle)
            else -> context.getString(R.string.notif_downloading, active.size)
        }
        val text = when {
            moving.isNotEmpty() -> Formatters.percent(moving.first().progress, u)
            paused -> ""
            else -> buildString {
                append(Formatters.speed(session.downloadRate, u))
                if (wanted > 0) {
                    append(" · ")
                    append(Formatters.percent(done.toFloat() / wanted, u))
                }
                active.firstOrNull()?.let { append(" · ").append(it.name) }
            }
        }
        val progress = when {
            moving.isNotEmpty() -> (moving.first().progress * 1000).toInt()
            wanted > 0 -> (done * 1000 / wanted).toInt()
            else -> 0
        }
        return ProgressContent(
            title = title,
            text = text,
            progress = progress,
            indeterminate = moving.isEmpty() && !paused && active.isNotEmpty() && wanted == 0L,
            canResume = paused || torrents.any { it.state == TorrentState.PAUSED },
        )
    }

    /** Ongoing notification with overall progress, speed and Pause / Resume / Exit buttons. */
    fun progress(context: Context, content: ProgressContent): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_graphite)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, content.progress, content.indeterminate)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_pause_all), serviceAction(context, TorrentService.ACTION_PAUSE_ALL, 1))
            .apply { if (content.canResume) addAction(0, context.getString(R.string.action_resume_all), serviceAction(context, TorrentService.ACTION_RESUME_ALL, 2)) }
            .addAction(0, context.getString(R.string.action_exit), serviceAction(context, TorrentService.ACTION_EXIT, 3))
            .build()

    fun progress(context: Context, session: SessionSnapshot, torrents: List<TorrentItem>): android.app.Notification =
        progress(context, progressContent(context, session, torrents))

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
