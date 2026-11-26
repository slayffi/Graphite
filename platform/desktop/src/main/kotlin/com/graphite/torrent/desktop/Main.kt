package com.graphite.torrent.desktop

import com.graphite.torrent.core.util.MagnetLink
import com.graphite.torrent.data.DatabaseFactory
import com.graphite.torrent.data.FileSystemStorage
import com.graphite.torrent.data.GraphiteServices
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.IncomingRequest
import kotlinx.coroutines.launch
import java.awt.Frame
import java.io.File
import javax.swing.UIManager
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Native look for the folder chooser; Compose itself does not use Swing styling.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    val instance = SingleInstance(DesktopPaths.dataDir)
    if (!instance.acquire()) {
        // Another Graphite is running: hand over the arguments (or just bring it to front) and quit.
        instance.forward(args.toList().ifEmpty { listOf(SHOW_WINDOW) })
        exitProcess(0)
    }

    val services = GraphiteServices.create(
        dataDir = DesktopPaths.dataDir,
        driver = DatabaseFactory.createDriver(DesktopPaths.database),
        storage = FileSystemStorage(DesktopPaths.defaultDownloads.absolutePath),
        nativeExtractDir = DesktopPaths.nativeDir.absolutePath,
    )
    var mainFrame: Frame? = null
    val platform = DesktopPlatformServices { mainFrame }
    val desktop = DesktopController(services)
    val env = AppEnvironment(
        controller = services.controller,
        local = services.controller,
        settings = services.settings,
        meta = services.meta,
        stats = services.stats,
        history = services.history,
        eventLog = services.eventLog,
        platform = platform,
        features = desktop.features(),
    )

    instance.listen { forwarded ->
        if (forwarded.contains(SHOW_WINDOW)) desktop.showWindow()
        handleArguments(env, forwarded)
        desktop.showWindow()
    }
    handleArguments(env, args.toList())

    services.scope.launch { services.controller.start() }

    runDesktopApp(env, services, desktop, onFrame = { mainFrame = it }, onExit = { instance.release() })
}

private const val SHOW_WINDOW = "--show"

/** Arguments are magnet links or paths to .torrent files (file association, browser, drag onto the exe). */
internal fun handleArguments(env: AppEnvironment, args: List<String>) {
    for (arg in args) {
        val trimmed = arg.trim().removeSurrounding("\"")
        when {
            trimmed.startsWith("magnet:", ignoreCase = true) -> env.submit(IncomingRequest.Magnet(trimmed))
            trimmed.lowercase().endsWith(".torrent") -> {
                val file = File(trimmed)
                if (file.isFile) env.submit(IncomingRequest.TorrentFile(file.name, file.readBytes()))
            }
            MagnetLink.findInText(trimmed) != null && !trimmed.startsWith("--") -> env.submit(IncomingRequest.Text(trimmed))
        }
    }
}
