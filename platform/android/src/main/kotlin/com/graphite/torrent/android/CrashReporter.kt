package com.graphite.torrent.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.graphite.torrent.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Local crash report: instead of silently closing, Graphite shows the error with "Copy" and "Share"
 * buttons ([CrashActivity], separate process). Nothing is sent anywhere automatically.
 */
object CrashReporter {
    private const val FILE = "last-crash.txt"
    const val PROCESS_SUFFIX = ":crash"

    fun install(app: Application) {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                show(app, save(app, error, thread.name))
            } catch (_: Throwable) {
                // Reporting must never hide the original problem behind another one.
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /** Shows the report for an error that was caught (for example the engine failed to start). */
    fun report(context: Context, error: Throwable) {
        runCatching { show(context, save(context, error, Thread.currentThread().name)) }
    }

    fun save(context: Context, error: Throwable, thread: String): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("Graphite ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}, thread: $thread")
            appendLine()
            append(trace)
        }
        runCatching { File(context.filesDir, FILE).writeText(text) }
        return text
    }

    private fun show(context: Context, report: String) {
        context.startActivity(
            Intent(context, CrashActivity::class.java)
                .putExtra(CrashActivity.EXTRA_REPORT, report.take(200_000))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    /** True in the separate process that only hosts [CrashActivity]. */
    fun isCrashProcess(app: Application): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault("")
        }
        return name.endsWith(PROCESS_SUFFIX)
    }
}
