package com.graphite.torrent.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** Per-user registry integration: autostart, magnet: protocol and .torrent association. */
object WindowsRegistry {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val CLASSES = "Software\\Classes"
    private const val PROG_ID = "Graphite.torrent"
    private val hkcu = WinReg.HKEY_CURRENT_USER

    /** Only the installed app (jpackage launcher) touches the registry; development runs never do. */
    private val exe: String? get() = DesktopPaths.executable?.takeIf { DesktopPaths.isWindows }

    fun setAutostart(enabled: Boolean) = runCatching {
        val path = exe ?: return@runCatching
        if (enabled) {
            Advapi32Util.registrySetStringValue(hkcu, RUN_KEY, "Graphite", "\"$path\" --autostart")
        } else if (Advapi32Util.registryValueExists(hkcu, RUN_KEY, "Graphite")) {
            Advapi32Util.registryDeleteValue(hkcu, RUN_KEY, "Graphite")
        }
    }

    fun setMagnetHandler(enabled: Boolean) = runCatching {
        val path = exe ?: return@runCatching
        val key = "$CLASSES\\magnet"
        val command = "\"$path\" \"%1\""
        if (enabled) {
            Advapi32Util.registryCreateKey(hkcu, CLASSES, "magnet")
            Advapi32Util.registrySetStringValue(hkcu, key, "", "URL:Magnet link")
            Advapi32Util.registrySetStringValue(hkcu, key, "URL Protocol", "")
            Advapi32Util.registryCreateKey(hkcu, key, "DefaultIcon")
            Advapi32Util.registrySetStringValue(hkcu, "$key\\DefaultIcon", "", "\"$path\",0")
            createPath("$key\\shell\\open\\command")
            Advapi32Util.registrySetStringValue(hkcu, "$key\\shell\\open\\command", "", command)
        } else if (ownsCommand("$key\\shell\\open\\command", path)) {
            Advapi32Util.registryDeleteKey(hkcu, "$key\\shell\\open\\command")
        }
    }

    fun setTorrentAssociation(enabled: Boolean) = runCatching {
        val path = exe ?: return@runCatching
        val progKey = "$CLASSES\\$PROG_ID"
        if (enabled) {
            createPath("$progKey\\shell\\open\\command")
            Advapi32Util.registrySetStringValue(hkcu, progKey, "", "BitTorrent file")
            Advapi32Util.registrySetStringValue(hkcu, "$progKey\\shell\\open\\command", "", "\"$path\" \"%1\"")
            createPath("$CLASSES\\.torrent")
            Advapi32Util.registrySetStringValue(hkcu, "$CLASSES\\.torrent", "", PROG_ID)
        } else if (Advapi32Util.registryKeyExists(hkcu, "$CLASSES\\.torrent") &&
            Advapi32Util.registryGetStringValue(hkcu, "$CLASSES\\.torrent", "") == PROG_ID
        ) {
            Advapi32Util.registrySetStringValue(hkcu, "$CLASSES\\.torrent", "", "")
        }
    }

    private fun ownsCommand(key: String, path: String): Boolean =
        Advapi32Util.registryKeyExists(hkcu, key) && Advapi32Util.registryGetStringValue(hkcu, key, "").contains(path)

    private fun createPath(path: String) {
        var current = ""
        for (part in path.split('\\')) {
            if (current.isNotEmpty()) Advapi32Util.registryCreateKey(hkcu, current, part)
            current = if (current.isEmpty()) part else "$current\\$part"
        }
    }
}

/** System-wide shortcuts via RegisterHotKey on a dedicated message-loop thread. */
class GlobalHotkeys(private val onHotkey: (Hotkey) -> Unit) {
    enum class Hotkey(val id: Int, val key: Char) { PAUSE_ALL(1, 'P'), RESUME_ALL(2, 'R'), SHOW(3, 'G'), MINI(4, 'M') }

    @Volatile private var threadId = 0
    private var worker: Thread? = null

    fun start() {
        if (!DesktopPaths.isWindows || worker != null) return
        worker = thread(name = "graphite-hotkeys", isDaemon = true) {
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            val user32 = User32.INSTANCE
            Hotkey.entries.forEach { user32.RegisterHotKey(null, it.id, MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, it.key.code) }
            val msg = WinUser.MSG()
            while (user32.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WM_HOTKEY) {
                    val id = msg.wParam.toInt()
                    Hotkey.entries.firstOrNull { it.id == id }?.let(onHotkey)
                }
            }
            Hotkey.entries.forEach { user32.UnregisterHotKey(null, it.id) }
        }
    }

    fun stop() {
        val id = threadId
        if (worker != null && id != 0) {
            User32.INSTANCE.PostThreadMessage(id, WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        }
        worker = null
        threadId = 0
    }

    private companion object {
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val MOD_NOREPEAT = 0x4000
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
    }
}

/** Sleep / shutdown after all downloads finished. */
object PowerActions {
    private interface PowrProf : StdCallLibrary {
        fun SetSuspendState(hibernate: Boolean, forceCritical: Boolean, disableWakeEvent: Boolean): Boolean
    }

    fun sleep() {
        if (!DesktopPaths.isWindows) return
        runCatching { Native.load("PowrProf", PowrProf::class.java).SetSuspendState(false, false, false) }
    }

    /** Shuts down in 60 seconds so the user can still cancel with "shutdown /a". */
    fun shutdown(message: String) {
        runCatching {
            if (DesktopPaths.isWindows) ProcessBuilder("shutdown.exe", "/s", "/t", "60", "/c", message).start()
            else ProcessBuilder("shutdown", "-h", "+1").start()
        }
    }
}

/** A short two-tone chime generated in code (no audio files bundled). */
object Chime {
    fun play() {
        thread(name = "graphite-chime", isDaemon = true) {
            runCatching {
                val rate = 44_100f
                val format = AudioFormat(rate, 16, 1, true, false)
                val tones = listOf(880.0 to 0.12, 1318.5 to 0.18)
                val samples = tones.sumOf { (it.second * rate).toInt() }
                val data = ByteArray(samples * 2)
                var offset = 0
                for ((freq, seconds) in tones) {
                    val n = (seconds * rate).toInt()
                    for (i in 0 until n) {
                        val envelope = minOf(1.0, i / 400.0, (n - i) / 1200.0)
                        val v = (sin(2 * PI * freq * i / rate) * envelope * 0.25 * Short.MAX_VALUE).toInt()
                        data[offset++] = (v and 0xFF).toByte()
                        data[offset++] = (v shr 8 and 0xFF).toByte()
                    }
                }
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format)
                line.start()
                line.write(data, 0, data.size)
                line.drain()
                line.close()
            }
        }
    }
}
