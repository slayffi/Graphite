package com.graphite.torrent.ui.designsystem.format

import com.graphite.torrent.ui.designsystem.theme.UnitLabels
import kotlin.math.abs
import kotlin.math.roundToLong

/** Locale-aware formatting of sizes, speeds, durations and percentages (binary units, 1024). */
object Formatters {
    private const val K = 1024.0

    fun bytes(value: Long, units: UnitLabels): String {
        val v = value.coerceAtLeast(0).toDouble()
        return when {
            v < K -> "${value.coerceAtLeast(0)} ${units.bytes}"
            v < K * K -> "${number(v / K, units)} ${units.kilobytes}"
            v < K * K * K -> "${number(v / (K * K), units)} ${units.megabytes}"
            v < K * K * K * K -> "${number(v / (K * K * K), units)} ${units.gigabytes}"
            else -> "${number(v / (K * K * K * K), units)} ${units.terabytes}"
        }
    }

    fun speed(bytesPerSecond: Long, units: UnitLabels): String = bytes(bytesPerSecond, units) + units.perSecond

    /** 1 decimal below 100, none above: "9,8 МБ", "123 МБ". */
    fun number(value: Double, units: UnitLabels): String {
        if (abs(value) >= 100) return value.roundToLong().toString()
        val tenths = (value * 10).roundToLong()
        val whole = tenths / 10
        val fraction = abs(tenths % 10)
        if (whole >= 100) return whole.toString()
        return "$whole${units.decimalSeparator}$fraction"
    }

    fun percent(progress: Float, units: UnitLabels): String {
        val p = (progress.coerceIn(0f, 1f) * 100)
        return when {
            p >= 100f -> "100%"
            p >= 10f -> "${p.toInt()}%"
            else -> {
                val tenths = (p * 10).toInt()
                "${tenths / 10}${units.decimalSeparator}${tenths % 10}%"
            }
        }
    }

    /** Short duration: "45s", "12m 05s", "3h 20m", "2d 4h"; negative = unknown (∞). */
    fun duration(seconds: Long, units: UnitLabels): String {
        if (seconds < 0) return units.infinity
        val d = seconds / 86_400
        val h = seconds % 86_400 / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return when {
            d > 0 -> "$d${units.days} $h${units.hours}"
            h > 0 -> "$h${units.hours} ${m.toString().padStart(2, '0')}${units.minutes}"
            m > 0 -> "$m${units.minutes} ${s.toString().padStart(2, '0')}${units.seconds}"
            else -> "$s${units.seconds}"
        }
    }

    /** Media time "1:05:09" / "4:09". */
    fun clock(millis: Long): String {
        val total = (millis.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
    }
}
