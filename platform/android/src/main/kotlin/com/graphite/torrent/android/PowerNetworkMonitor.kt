package com.graphite.torrent.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.graphite.torrent.core.controller.TorrentController
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** "Only over Wi-Fi", "only while charging" and "pause on low battery". */
class PowerNetworkMonitor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val controller: TorrentController,
    private val scope: CoroutineScope,
) {
    private data class Power(val level: Int, val plugged: Boolean)

    private val unmetered = MutableStateFlow(true)
    private val power = MutableStateFlow(Power(100, true))

    fun start() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                unmetered.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }

            override fun onLost(network: Network) {
                unmetered.value = false
            }
        })
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                power.value = Power(if (level >= 0) level * 100 / scale else 100, plugged)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { receiver.onReceive(context, it) }

        scope.launch {
            combine(settings.settings, unmetered, power) { s, wifi, p ->
                Triple(
                    s.wifiOnly && !wifi,
                    s.chargingOnly && !p.plugged,
                    s.pauseOnLowBattery && !p.plugged && p.level < s.lowBatteryThreshold,
                )
            }.collect { (noWifi, notCharging, lowBattery) ->
                controller.setPauseReason(PauseReason.NO_WIFI, noWifi)
                controller.setPauseReason(PauseReason.NOT_CHARGING, notCharging)
                controller.setPauseReason(PauseReason.LOW_BATTERY, lowBattery)
            }
        }
    }
}
