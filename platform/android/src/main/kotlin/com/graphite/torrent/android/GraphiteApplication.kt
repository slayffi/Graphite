package com.graphite.torrent.android

import android.app.Application
import com.graphite.torrent.core.controller.ControllerNotice
import com.graphite.torrent.data.DatabaseFactory
import com.graphite.torrent.data.GraphiteServices
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.AppFeatures
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** Owns the engine for the whole process lifetime; the UI and the service attach to it. */
class GraphiteApplication : Application() {
    lateinit var services: GraphiteServices
        private set
    lateinit var env: AppEnvironment
        private set
    lateinit var platform: AndroidPlatformServices
        private set
    val bridge = ActivityBridge()

    /** Emitted when the user chose "Exit" in the notification so the activity can close. */
    val exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Extra features registered by stage-4 modules (streaming, remote, RSS...). */
    var features: AppFeatures = AppFeatures()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        val storage = AndroidStorage(this)
        services = GraphiteServices.create(
            dataDir = File(filesDir, "graphite"),
            driver = DatabaseFactory.createDriver(this),
            storage = storage,
        )
        platform = AndroidPlatformServices(this, storage, bridge)
        env = AppEnvironment(
            controller = services.controller,
            local = services.controller,
            settings = services.settings,
            meta = services.meta,
            stats = services.stats,
            history = services.history,
            eventLog = services.eventLog,
            platform = platform,
            features = features,
        )
        services.scope.launch { services.controller.start() }

        // Keep the foreground service running while something downloads.
        services.scope.launch {
            services.controller.torrents
                .map { list -> list.any { it.state in TorrentService.ACTIVE } }
                .distinctUntilChanged()
                .collect { active -> if (active) TorrentService.start(this@GraphiteApplication) }
        }

        // System notification when a download completes.
        services.scope.launch {
            services.controller.notices.collect { notice ->
                if (notice is ControllerNotice.Completed && services.settings.settings.value.notifyOnComplete) {
                    Notifications.completed(this@GraphiteApplication, notice.id, notice.name)
                }
            }
        }
    }

    /** Saves resume data and stops the engine (notification "Exit"). */
    suspend fun shutdown() {
        services.controller.stop()
        exitRequests.tryEmit(Unit)
    }
}
