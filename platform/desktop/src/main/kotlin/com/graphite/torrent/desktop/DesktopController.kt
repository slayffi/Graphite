package com.graphite.torrent.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.graphite.torrent.core.controller.ControllerNotice
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.core.settings.CompletionSystemAction
import com.graphite.torrent.data.GraphiteServices
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.AppFeatures
import com.graphite.torrent.ui.app.DesktopFeature
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.notify_completed_title
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import java.awt.Frame
import java.awt.Taskbar
import javax.swing.SwingUtilities

/** Windows integration around the main window: tray notices, taskbar progress, hotkeys, registry, power. */
class DesktopController(private val services: GraphiteServices) {
    val windowVisible = MutableStateFlow(true)
    val miniVisible = MutableStateFlow(false)
    private var window: ComposeWindow? = null
    private val jobs = ArrayList<Job>()
    private var hotkeys: GlobalHotkeys? = null
    var extraFeatures: AppFeatures? = null

    fun features(): AppFeatures {
        val desktopFeature = object : DesktopFeature {
            override fun openMiniWindow() {
                miniVisible.value = true
            }
        }
        val extra = extraFeatures
        return AppFeatures(
            streaming = extra?.streaming,
            remote = extra?.remote,
            rss = extra?.rss,
            scheduler = extra?.scheduler,
            backup = extra?.backup,
            desktop = desktopFeature,
        )
    }

    fun attachWindow(w: ComposeWindow) {
        window = w
    }

    fun showWindow() {
        windowVisible.value = true
        SwingUtilities.invokeLater {
            window?.let { w ->
                if (w.extendedState and Frame.ICONIFIED != 0) w.extendedState = w.extendedState and Frame.ICONIFIED.inv()
                w.toFront()
                w.requestFocus()
            }
        }
    }

    fun hideWindow() {
        windowVisible.value = false
    }

    fun toggleMiniWindow() {
        miniVisible.value = !miniVisible.value
    }

    fun start(env: AppEnvironment, tray: TrayState, quit: () -> Unit) {
        val scope = services.scope
        val settings = env.settings

        // Completion notice + sound
        jobs += scope.launch {
            env.controller.notices.collect { notice ->
                if (notice is ControllerNotice.Completed) {
                    val s = settings.settings.value
                    if (s.notifyOnComplete) tray.sendNotification(Notification(getString(Res.string.notify_completed_title), notice.name, Notification.Type.Info))
                    if (s.soundOnComplete) Chime.play()
                }
            }
        }

        // Action after everything finished
        jobs += scope.launch {
            var hadActive = false
            env.controller.torrents.collect { list ->
                val active = list.any { it.state in ACTIVE_STATES }
                val justFinished = list.any { it.state == TorrentState.COMPLETED && System.currentTimeMillis() - it.completedAt < 120_000 }
                if (hadActive && !active && justFinished) {
                    when (settings.settings.value.completionAction) {
                        CompletionSystemAction.NONE -> Unit
                        CompletionSystemAction.SLEEP -> PowerActions.sleep()
                        CompletionSystemAction.SHUTDOWN -> PowerActions.shutdown("Graphite: all downloads finished")
                        CompletionSystemAction.QUIT_APP -> quit()
                    }
                }
                hadActive = active
            }
        }

        // Progress on the taskbar button
        jobs += scope.launch {
            val taskbar = runCatching { if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar() else null }.getOrNull()
            val supported = taskbar?.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW) == true
            while (isActive && supported) {
                val w = window
                val list = env.controller.torrents.value.filter { it.state in ACTIVE_STATES || it.state == TorrentState.PAUSED }
                if (w != null) {
                    val wanted = list.sumOf { it.wantedBytes }
                    val done = list.sumOf { it.doneBytes }
                    val anyActive = list.any { it.state in ACTIVE_STATES }
                    val anyError = env.controller.torrents.value.any { it.state == TorrentState.ERROR }
                    SwingUtilities.invokeLater {
                        runCatching {
                            if (wanted <= 0 || list.isEmpty()) {
                                taskbar!!.setWindowProgressState(w, Taskbar.State.OFF)
                            } else {
                                taskbar!!.setWindowProgressValue(w, (done * 100 / wanted).toInt().coerceIn(0, 100))
                                taskbar.setWindowProgressState(
                                    w,
                                    when {
                                        anyError -> Taskbar.State.ERROR
                                        anyActive -> Taskbar.State.NORMAL
                                        else -> Taskbar.State.PAUSED
                                    },
                                )
                            }
                        }
                    }
                }
                delay(1000)
            }
        }

        // Registry integration follows the settings.
        jobs += scope.launch {
            settings.settings.map { Triple(it.autostart, it.associateMagnetLinks, it.associateTorrentFiles) }
                .distinctUntilChanged()
                .collect { (autostart, magnet, torrent) ->
                    WindowsRegistry.setAutostart(autostart)
                    WindowsRegistry.setMagnetHandler(magnet)
                    WindowsRegistry.setTorrentAssociation(torrent)
                }
        }

        // Global hotkeys
        jobs += scope.launch {
            settings.settings.map { it.globalHotkeys }.distinctUntilChanged().collect { enabled ->
                hotkeys?.stop()
                hotkeys = null
                if (enabled) {
                    hotkeys = GlobalHotkeys { key ->
                        when (key) {
                            GlobalHotkeys.Hotkey.PAUSE_ALL -> scope.launch { env.controller.pauseAll() }
                            GlobalHotkeys.Hotkey.RESUME_ALL -> scope.launch { env.controller.resumeAll() }
                            GlobalHotkeys.Hotkey.SHOW -> showWindow()
                            GlobalHotkeys.Hotkey.MINI -> toggleMiniWindow()
                        }
                    }.also { it.start() }
                }
            }
        }
    }

    fun stop() {
        hotkeys?.stop()
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private companion object {
        val ACTIVE_STATES = setOf(TorrentState.DOWNLOADING, TorrentState.METADATA, TorrentState.CHECKING, TorrentState.QUEUED, TorrentState.MOVING)
    }
}
