package com.graphite.torrent.android

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.data.BackgroundPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while downloads run. It shows overall progress
 * with Pause / Resume / Exit actions and stops itself once nothing is active for a while.
 */
class TorrentService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var probeUntil = 0L
    private var probeScheduled = false

    private val app get() = application as GraphiteApplication

    override fun onCreate() {
        super.onCreate()
        if (!app.ready) {
            stopSelf()
            return
        }
        val controller = app.services.controller
        val content = Notifications.progressContent(this, controller.session.value, controller.torrents.value)
        ServiceCompat.startForeground(
            this, Notifications.ID_SERVICE, Notifications.progress(this, content),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        instance = this
        // Off the main thread: formatting and posting the notification must never delay the UI.
        lifecycleScope.launch(Dispatchers.Default) { updateLoop(content) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!app.ready) return START_NOT_STICKY
        val controller = app.services.controller
        when (intent?.action) {
            ACTION_PAUSE_ALL -> lifecycleScope.launch { controller.pauseAll() }
            ACTION_RESUME_ALL -> lifecycleScope.launch { controller.resumeAll() }
            ACTION_EXIT -> lifecycleScope.launch {
                app.shutdown()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun updateLoop(shown: Notifications.ProgressContent) {
        var posted = shown
        var idleSeconds = 0
        var quietSeconds = 0
        val manager = NotificationManagerCompat.from(this)
        val power = getSystemService(PowerManager::class.java)
        val controller = app.services.controller
        while (lifecycleScope.isActive) {
            val torrents = controller.torrents.value
            val session = controller.session.value
            // Screen on: the user may be looking at the notification. Screen off: nobody is.
            val tick = if (power.isInteractive) TICK_SCREEN_ON_SECONDS else TICK_SCREEN_OFF_SECONDS

            idleSeconds = if (needsService(torrents, session)) 0 else idleSeconds + tick
            if (idleSeconds >= IDLE_STOP_SECONDS) {
                stopSelf()
                return
            }

            // The CPU and Wi-Fi are kept awake only while something really happens. A download without peers,
            // or one waiting for Wi-Fi or the charger, must not keep the phone awake for hours.
            val running = session.pauseReasons.isEmpty()
            val working = torrents.any { it.state == TorrentState.MOVING } ||
                (running && (session.downloadRate > 0 || torrents.any { it.state == TorrentState.CHECKING }))
            // While paused the stall clock does not run: when the pause ends, peers get the full time again.
            quietSeconds = if (working || !running) 0 else quietSeconds + tick
            val searching = running && torrents.any { it.state in ACTIVE } && quietSeconds < STALL_RELEASE_SECONDS
            val probing = SystemClock.elapsedRealtime() < probeUntil
            val held = working || searching || probing
            setLocks(held, multicast = app.services.settings.settings.value.enableLsd)
            // Asleep, nothing in this process runs: not the schedule, not the search for peers. A rare alarm
            // wakes the phone for a moment to look again, so a stalled or waiting download is never forgotten.
            scheduleProbe(needed = !held)

            val content = Notifications.progressContent(this, session, torrents)
            if (content != posted) {
                posted = content
                @SuppressLint("MissingPermission")
                runCatching { manager.notify(Notifications.ID_SERVICE, Notifications.progress(this, content)) }
            }
            delay(tick * 1000L)
        }
    }

    /** The alarm fired: stay awake for a short while and let the loop decide whether it is worth more. */
    private fun beginProbe() {
        probeScheduled = false
        probeUntil = SystemClock.elapsedRealtime() + PROBE_AWAKE_MS
        setLocks(held = true, multicast = false)
    }

    private fun probeIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 0, Intent(this, ProbeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @Synchronized
    private fun scheduleProbe(needed: Boolean) {
        if (needed == probeScheduled) return
        runCatching {
            val alarms = getSystemService(AlarmManager::class.java)
            if (needed) {
                // Inexact and allowed in Doze: needs no special permission, Android may deliver it a little later.
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PROBE_INTERVAL_MS, probeIntent())
            } else {
                alarms.cancel(probeIntent())
            }
            probeScheduled = needed
        }
    }

    @SuppressLint("WakelockTimeout")
    @Synchronized
    private fun setLocks(held: Boolean, multicast: Boolean) {
        if (!held) {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
            return
        }
        runCatching {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Graphite:downloads")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            if (!lock.isHeld) lock.acquire()
        }
        runCatching {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            val lock = wifiLock ?: wifi.createWifiLock(mode, "Graphite:downloads").apply { setReferenceCounted(false) }.also { wifiLock = it }
            if (!lock.isHeld) lock.acquire()
            // Local peer discovery (LSD) uses multicast; without LSD the radio does not have to listen for it.
            val mc = multicastLock ?: wifi.createMulticastLock("Graphite:lsd").apply { setReferenceCounted(false) }.also { multicastLock = it }
            if (multicast && !mc.isHeld) mc.acquire() else if (!multicast && mc.isHeld) mc.release()
        }
    }

    override fun onDestroy() {
        instance = null
        scheduleProbe(needed = false)
        setLocks(held = false, multicast = false)
        super.onDestroy()
    }

    /** Wakes the service from deep sleep; see [scheduleProbe]. */
    class ProbeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            instance?.beginProbe()
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.graphite.torrent.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.graphite.torrent.RESUME_ALL"
        const val ACTION_EXIT = "com.graphite.torrent.EXIT"
        private const val IDLE_STOP_SECONDS = 20
        private const val TICK_SCREEN_ON_SECONDS = 2
        private const val TICK_SCREEN_OFF_SECONDS = 10

        /** Without a single byte for this long the wake locks are released until data flows again. */
        private const val STALL_RELEASE_SECONDS = 10 * 60

        /** Sleeping with unfinished downloads: wake up this often, for this long, to try again. */
        private const val PROBE_INTERVAL_MS = 15 * 60_000L
        private const val PROBE_AWAKE_MS = 45_000L

        @Volatile private var instance: TorrentService? = null
        val ACTIVE get() = BackgroundPolicy.ACTIVE

        fun needsService(torrents: List<TorrentItem>, session: SessionSnapshot): Boolean = BackgroundPolicy.needsService(torrents, session)

        /** Starts the service; ignored when Android does not allow a foreground start right now. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TorrentService::class.java)) }
        }
    }
}
