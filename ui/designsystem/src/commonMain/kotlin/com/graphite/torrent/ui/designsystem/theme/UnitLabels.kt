package com.graphite.torrent.ui.designsystem.theme

import androidx.compose.runtime.Immutable

/** Localized unit names and separators used by the number formatters. */
@Immutable
data class UnitLabels(
    val bytes: String,
    val kilobytes: String,
    val megabytes: String,
    val gigabytes: String,
    val terabytes: String,
    val perSecond: String,
    val seconds: String,
    val minutes: String,
    val hours: String,
    val days: String,
    val decimalSeparator: Char,
    val infinity: String = "∞",
) {
    /** Unit of speed limits entered by the user ("KB/s", "КБ/с"). */
    val kilobytesPerSecond: String get() = kilobytes + perSecond

    companion object {
        val English = UnitLabels("B", "KB", "MB", "GB", "TB", "/s", "s", "m", "h", "d", '.')
        val Russian = UnitLabels("Б", "КБ", "МБ", "ГБ", "ТБ", "/с", "с", "м", "ч", "д", ',')
    }
}
