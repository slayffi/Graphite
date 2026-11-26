package com.graphite.torrent.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.graphite.torrent.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Finds out why the previous start failed, even when no Java code could react: native crashes in
 * libtorrent, an ANR, or a crash before any window was visible (Android blocks activities started
 * from a dying background process).
 *
 * Every start writes the steps it reaches to a small file. A start that never got marked healthy, or
 * an abnormal exit recorded by Android itself (API 30+), makes the next start show a report instead of
 * starting the engine again. All of it stays on the device.
 */
object StartupGuard {
    private const val MARKER = "startup-steps.txt"
    private const val ACK = "startup-ack.txt"
    private const val HEALTHY = "HEALTHY"
    private const val EXCEPTION = "EXCEPTION"

    private val lock = Any()
    private var dir: File? = null
    private val startedAt = SystemClock.elapsedRealtime()

    /** Called first thing in Application.attachBaseContext. Returns a report about the previous start, or null. */
    fun begin(context: Context): String? {
        val files = context.noBackupFilesDir.apply { mkdirs() }
        dir = files
        val marker = File(files, MARKER)
        val previous = runCatching { marker.takeIf { it.exists() }?.readText() }.getOrNull()
        val previousFailed = previous != null && (HEALTHY !in previous || EXCEPTION in previous)
        val exit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { lastAbnormalExit(context, lastAck(files)) }.getOrNull() else null

        val report = if (previousFailed || exit != null) buildReport(context, previous, exit) else null
        runCatching { marker.writeText("Start ${now()}\n") }
        step("attach")
        return report
    }

    /** Records how far the start got. Cheap: one appended line. */
    fun step(name: String) = append("+${SystemClock.elapsedRealtime() - startedAt} ms  $name")

    fun recordException(thread: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        append("$EXCEPTION in thread \"$thread\":\n$trace")
    }

    /** The app showed its UI and kept running for a few seconds. */
    fun healthy() = append(HEALTHY)

    /** The report was shown: the next start may try normally again. */
    fun acknowledge(context: Context) {
        val files = context.noBackupFilesDir
        runCatching { File(files, ACK).writeText(System.currentTimeMillis().toString()) }
        runCatching { File(files, MARKER).delete() }
    }

    private fun append(line: String) {
        val d = dir ?: return
        synchronized(lock) { runCatching { File(d, MARKER).appendText(line + "\n") } }
    }

    private fun lastAck(files: File): Long = runCatching { File(files, ACK).readText().trim().toLong() }.getOrDefault(0L)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun lastAbnormalExit(context: Context, since: Long): ApplicationExitInfo? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val abnormal = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        )
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            .firstOrNull { it.timestamp > since && it.processName == context.packageName && it.reason in abnormal }
    }

    private fun buildReport(context: Context, steps: String?, exit: Any?): String = buildString {
        appendLine("Graphite ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()
        appendLine("== Previous start ==")
        appendLine(steps?.trimEnd() ?: "(no record)")
        if (exit != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appendLine()
            appendExit(exit as ApplicationExitInfo)
        }
        val log = ownLog()
        if (log.isNotBlank()) {
            appendLine()
            appendLine("== Log ==")
            append(log)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun StringBuilder.appendExit(info: ApplicationExitInfo) {
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            else -> info.reason.toString()
        }
        appendLine("== Android exit record ==")
        appendLine("Reason: $reason, status ${info.status}, at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))}")
        info.description?.let { appendLine("Description: $it") }
        // ANR: text trace. Native crash (API 31+): tombstone in protobuf; its readable strings hold the backtrace.
        val raw = runCatching { info.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: return
        val text = if (info.reason == ApplicationExitInfo.REASON_ANR) {
            String(raw).lineSequence().take(120).joinToString("\n")
        } else {
            readableStrings(raw).take(150).joinToString("\n")
        }
        if (text.isNotBlank()) {
            appendLine("Trace:")
            appendLine(text)
        }
    }

    /** Printable ASCII runs of a binary blob (like `strings`). */
    private fun readableStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                current.append(c.toChar())
            } else {
                if (current.length >= 6) out += current.toString()
                current.setLength(0)
            }
        }
        if (current.length >= 6) out += current.toString()
        return out
    }

    /** Errors this app logged recently (apps may read their own log lines, including the crashed process). */
    private fun ownLog(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(3, TimeUnit.SECONDS)
        lines.filter { line ->
            val level = line.split(Regex("\\s+")).getOrNull(4)
            level == "E" || level == "F" || "AndroidRuntime" in line || " libc " in line || "DEBUG" in line
        }.takeLast(120).joinToString("\n")
    }.getOrDefault("")

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
