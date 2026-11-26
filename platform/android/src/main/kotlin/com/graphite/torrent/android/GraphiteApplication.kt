package com.graphite.torrent.android

import android.app.Application
import com.graphite.torrent.core.controller.ControllerNotice
import com.graphite.torrent.data.DatabaseFactory
import com.graphite.torrent.data.GraphiteServices
import com.graphite.torrent.data.automation.FileWatchFolderSource
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.AppFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.File

/** Owns the engine for the whole process lifetime; the UI, the service, the widget and the tile attach to it. */
class GraphiteApplication : Application() {
    lateinit var services: GraphiteServices
        private set
    lateinit var env: AppEnvironment
        private set
    lateinit var platform: AndroidPlatformServices
        private set
    lateinit var remote: AndroidRemote
        private set
    val bridge = ActivityBridge()

    /** Emitted when the user chose "Exit" in the notification so the activity can close. */
    val exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        // The crash screen runs in its own process and needs none of the services.
        if (CrashReporter.isCrashProcess(this)) return
        CrashReporter.install(this)
        Notifications.createChannels(this)
        val storage = AndroidStorage(this)
        services = GraphiteServices.create(
            dataDir = File(filesDir, "graphite"),
            driver = DatabaseFactory.createDriver(this),
            storage = storage,
            watchSources = listOf(FileWatchFolderSource(), SafWatchFolderSource(this)),
        )
        platform = AndroidPlatformServices(this, storage, bridge)
        remote = AndroidRemote(services, services.scope)
        env = AppEnvironment(
            // The UI talks to the switchable controller: local engine, or the paired PC in remote mode.
            controller = services.switchable,
            local = services.controller,
            settings = services.settings,
            meta = services.meta,
            stats = services.stats,
            history = services.history,
            eventLog = services.eventLog,
            platform = platform,
            features = AppFeatures(
                streaming = AndroidStreaming(this, services, storage),
                remote = remote,
                rss = services.rss,
                scheduler = services.scheduler,
                backup = services.backup,
            ),
        )
        services.scope.launch {
            try {
                services.controller.start()
                services.startAutomation()
                remote.restore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Without the engine the app is useless: show what went wrong instead of an empty list.
                CrashReporter.report(this@GraphiteApplication, e)
            }
        }
        PowerNetworkMonitor(this, services.settings, services.controller, services.scope).start()

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

        // Home screen widget and quick settings tile follow the local engine.
        services.scope.launch {
            combine(services.controller.torrents, services.controller.session) { t, s -> WidgetSummary.of(t, s) }
                .distinctUntilChanged()
                .sample(WIDGET_REFRESH_MS)
                .collect { summary ->
                    GraphiteWidget.push(this@GraphiteApplication, summary)
                    GraphiteTileService.refresh(this@GraphiteApplication)
                }
        }
    }

    /** Restarts the engine and the automation after "Exit" (called when the UI comes back). */
    suspend fun ensureRunning() {
        services.controller.start()
        services.startAutomation()
    }

    /** Saves resume data and stops the engine (notification "Exit"). */
    suspend fun shutdown() {
        services.stopAutomation()
        services.controller.stop()
        exitRequests.tryEmit(Unit)
    }

    private companion object {
        const val WIDGET_REFRESH_MS = 3_000L
    }
}
