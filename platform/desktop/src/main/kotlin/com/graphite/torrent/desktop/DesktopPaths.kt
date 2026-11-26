package com.graphite.torrent.desktop

import java.io.File

/** Per-user folders of the desktop app. */
object DesktopPaths {
    private val os = System.getProperty("os.name").lowercase()
    val isWindows: Boolean = os.contains("win")
    val isMac: Boolean = os.contains("mac")

    val dataDir: File by lazy {
        val dir = when {
            isWindows -> File(System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Local"), "Graphite")
            isMac -> File(System.getProperty("user.home"), "Library/Application Support/Graphite")
            else -> File(System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"), "graphite")
        }
        dir.mkdirs()
        dir
    }

    val defaultDownloads: File by lazy {
        File(File(System.getProperty("user.home"), "Downloads"), "Graphite").apply { mkdirs() }
    }

    val nativeDir: File get() = File(dataDir, "native")
    val database: File get() = File(dataDir, "graphite.db")

    /** Folder with bundled resources of the installed app (Compose `appResourcesRootDir`). */
    val appResources: File? get() = System.getProperty("compose.application.resources.dir")?.let(::File)

    /** Executable of the installed app (set by the jpackage launcher). */
    val executable: String? get() = System.getProperty("jpackage.app-path")
}
