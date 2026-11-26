package com.graphite.torrent.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** Colours the native Windows title bar so it matches the app background (no custom chrome needed). */
object WindowsTheme {
    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwmapi? by lazy {
        if (!DesktopPaths.isWindows) null else runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull()
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    /** [argb] colours are converted to COLORREF (0x00BBGGRR). Unsupported attributes are ignored by Windows 10. */
    fun apply(window: Window, captionArgb: Int, textArgb: Int, borderArgb: Int) {
        val api = dwm ?: return
        val hwnd = runCatching { WinDef.HWND(Native.getWindowPointer(window)) }.getOrNull() ?: return
        api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(1), 4)
        api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(colorRef(captionArgb)), 4)
        api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, IntByReference(colorRef(textArgb)), 4)
        api.DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, IntByReference(colorRef(borderArgb)), 4)
    }

    private fun colorRef(argb: Int): Int {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
