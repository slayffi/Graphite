package com.graphite.torrent.android

import android.app.Application
import androidx.work.Configuration
import android.util.Log
import com.graphite.torrent.core.settings.DiskIoMode
import com.graphite.torrent.core.engine.NativeLibraries
import android.content.Context
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
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.File

/** Owns the engine for the whole process lifetime; the UI, the service, the widget and the tile attach to it. */
class GraphiteApplication : Application(), Configuration.Provider {
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

    /** Report about a failed previous start; when set, this start only shows it (safe mode). */
    var safeModeReport: String? = null
        private set

    /** True once the services exist; the widget, the tile and the service check it. */
    @Volatile var ready = false
        private set

    /** The start animation was already played in this process. */
    var splashShown = false

    /** WorkManager is initialized lazily with this (see the manifest); only the widget needs it. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (CrashReporter.isCrashProcess()) return
        // First of all: library initializers (content providers) run before onCreate, and a crash there
        // must be recorded too.
        CrashReporter.install(this)
        // Runs before content providers and onCreate, so even very early failures are caught next time.
        // The watchdog goes first: everything after it, StartupGuard included, is covered.
        StartupGuard.watchMainThread(base)
        safeModeReport = runCatching { StartupGuard.begin(base) }.getOrNull()
    }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        // The crash screen runs in its own process and needs none of the services.
        if (CrashReporter.isCrashProcess()) return
        StartupGuard.step("onCreate")
        if (safeModeReport != null) {
            // The previous start failed: MainActivity shows the report instead of starting the engine again.
            StartupGuard.step(StartupGuard.SAFE_MODE)
            return
        }
        Notifications.createChannels(this)
        val storage = AndroidStorage(this)
        StartupGuard.step("services")
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
        ready = true
        StartupGuard.step("services ready")
        services.scope.launch {
            try {
                applyAndroidDefaults()
                // Loading the native library takes a while: here, not on the UI thread, so the first frame is not held back.
                StartupGuard.step("native library")
                NativeLibraries.ensureLoaded(null)
                StartupGuard.step("engine start")
                services.controller.start()
                StartupGuard.step("engine running")
                services.startAutomation()
                remote.restore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Without the engine the app is useless: show what went wrong instead of an empty list.
                CrashReporter.report(this@GraphiteApplication, e)
            } catch (e: LinkageError) {
                // The native library does not load on this device (unsupported CPU, damaged install).
                CrashReporter.report(this@GraphiteApplication, e)
            }
        }
        PowerNetworkMonitor(this, services.settings, services.controller, services.scope).start()

        // Keep the foreground service running while something downloads, moves or waits for a rule to clear.
        services.scope.launch {
            combine(services.controller.torrents, services.controller.session) { t, s -> TorrentService.needsService(t, s) }
                .distinctUntilChanged()
                .collect { needed -> if (needed) TorrentService.start(this@GraphiteApplication) }
        }

        // System notification when a download completes.
        services.scope.launch {
            services.controller.notices.collect { notice ->
                if (notice is ControllerNotice.Completed && services.settings.settings.value.notifyOnComplete) {
                    Notifications.completed(this@GraphiteApplication, notice.id, notice.name)
                }
            }
        }

        // Home screen widget: only what it displays (rounded), and not more often than is useful on a home screen.
        services.scope.launch {
            combine(services.controller.torrents, services.controller.session) { t, s -> WidgetSummary.of(t, s).rounded() }
                .distinctUntilChanged()
                .sample(WIDGET_REFRESH_MS)
                .collect { summary -> GraphiteWidget.push(this@GraphiteApplication, summary) }
        }
    }

    /**
     * libtorrent 2's memory-mapped disk I/O is unreliable on Android's shared storage (FUSE) and on
     * 32-bit devices: use pread/pwrite there. Applied once, the user can still change it.
     */
    private suspend fun applyAndroidDefaults() {
        val prefs = getSharedPreferences("graphite_android", MODE_PRIVATE)
        if (prefs.getBoolean("defaults_v1", false)) return
        services.settings.update { it.copy(diskIoMode = DiskIoMode.POSIX) }
        prefs.edit().putBoolean("defaults_v1", true).apply()
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
        const val WIDGET_REFRESH_MS = 10_000L
    }
}
