package com.graphite.torrent.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.graphite.torrent.R
import kotlin.system.exitProcess

/**
 * Shown right after a crash (own process). Some firmwares (MIUI, ColorOS) refuse to open it from a
 * dying process; then MainActivity shows the same screen on the next start.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT).orEmpty()
        // Seen: the next start of Graphite tries normally again.
        StartupGuard.acknowledge(this)
        ReportScreen.show(this, report)
    }

    companion object {
        const val EXTRA_REPORT = "report"
    }
}

/**
 * The error report screen. Plain Android views on purpose: it must work even when Compose, fonts or
 * the engine are the reason for the failure.
 */
object ReportScreen {
    private val BG = Color.parseColor("#0B0B0C")
    private val SURFACE = Color.parseColor("#141416")
    private val BORDER = Color.parseColor("#2A2A2E")
    private val TEXT = Color.parseColor("#EDEDED")
    private val SECONDARY = Color.parseColor("#9A9AA0")
    private val ACCENT = Color.parseColor("#C8C8CC")
    private val ON_ACCENT = Color.parseColor("#0B0B0C")

    fun show(activity: Activity, report: String) {
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = BG
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = BG
        activity.setContentView(build(activity, report))
    }

    /** Starts Graphite again in a fresh process. */
    fun restart(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?: Intent(activity, MainActivity::class.java)
        activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity.finishAffinity()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    private fun build(activity: Activity, report: String): View {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

        fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(activity).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) ON_ACCENT else TEXT)
            background = GradientDrawable().apply {
                setColor(if (primary) ACCENT else SURFACE)
                cornerRadius = dp(24).toFloat()
                if (!primary) setStroke(dp(1), BORDER)
            }
            minHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)).apply { marginStart = dp(8) }
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }

        val pad = dp(16)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(pad, pad + dp(24), pad, pad + dp(16))
            fitsSystemWindows = true
        }
        root.addView(text(activity.getString(R.string.crash_title), 20f, TEXT, bold = true))
        root.addView(text(activity.getString(R.string.crash_message), 14f, SECONDARY).apply { setPadding(0, dp(8), 0, dp(16)) })

        var fullReport = report
        val trace = text(report, 11f, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        // The app's own log lines are added once read (in the background, it can be slow).
        if ("== Log ==" !in report) {
            StartupGuard.collectLogAsync { log ->
                if (log.isNotBlank()) activity.runOnUiThread {
                    fullReport = "$report\n\n== Log ==\n$log"
                    trace.text = fullReport
                }
            }
        }
        val box = ScrollView(activity).apply {
            background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(12).toFloat(); setStroke(dp(1), BORDER) }
            addView(HorizontalScrollView(activity).apply { addView(trace) })
        }
        root.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(button(activity.getString(R.string.crash_restart), primary = false) { restart(activity) })
        buttons.addView(button(activity.getString(R.string.crash_share), primary = false) {
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fullReport), null))
        })
        buttons.addView(button(activity.getString(R.string.crash_copy), primary = true) {
            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Graphite report", fullReport))
            Toast.makeText(activity, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        })
        root.addView(buttons)
        return root
    }
}
