package com.graphite.torrent.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.graphite.torrent.core.model.TorrentState
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

    private val app get() = application as GraphiteApplication

    override fun onCreate() {
        super.onCreate()
        val notification = Notifications.progress(this, app.services.controller.session.value, app.services.controller.torrents.value)
        ServiceCompat.startForeground(
            this, Notifications.ID_SERVICE, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        acquireLocks()
        lifecycleScope.launch { updateLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
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

    private suspend fun updateLoop() {
        var idleSeconds = 0
        val manager = NotificationManagerCompat.from(this)
        while (lifecycleScope.isActive) {
            val torrents = app.services.controller.torrents.value
            val session = app.services.controller.session.value
            val active = torrents.any { it.state in ACTIVE }
            idleSeconds = if (active) 0 else idleSeconds + 1
            if (idleSeconds >= IDLE_STOP_SECONDS) {
                stopSelf()
                return
            }
            @SuppressLint("MissingPermission")
            runCatching { manager.notify(Notifications.ID_SERVICE, Notifications.progress(this, session, torrents)) }
            delay(1000)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Graphite:downloads")
                .apply { setReferenceCounted(false); acquire() }
        }
        runCatching {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "Graphite:downloads").apply { setReferenceCounted(false); acquire() }
            // Local peer discovery (LSD) uses multicast.
            multicastLock = wifi.createMulticastLock("Graphite:lsd").apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        runCatching { multicastLock?.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.graphite.torrent.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.graphite.torrent.RESUME_ALL"
        const val ACTION_EXIT = "com.graphite.torrent.EXIT"
        private const val IDLE_STOP_SECONDS = 20
        val ACTIVE = setOf(TorrentState.DOWNLOADING, TorrentState.METADATA, TorrentState.CHECKING, TorrentState.QUEUED, TorrentState.MOVING)

        /** Starts the service; ignored when Android does not allow a foreground start right now. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TorrentService::class.java)) }
        }
    }
}
