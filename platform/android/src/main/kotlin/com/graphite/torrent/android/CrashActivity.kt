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
 * Shown instead of a silent close when Graphite fails. Plain Android views on purpose: it must work
 * even when Compose, fonts or the engine are the reason for the failure. Runs in its own process.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT).orEmpty()
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }
        root.addView(text(getString(R.string.crash_title), 20f, TEXT, bold = true))
        root.addView(text(getString(R.string.crash_message), 14f, SECONDARY).apply { setPadding(0, dp(8), 0, dp(16)) })

        val trace = text(report, 11f, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val box = ScrollView(this).apply {
            background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(12).toFloat(); setStroke(dp(1), BORDER) }
            addView(HorizontalScrollView(this@CrashActivity).apply { addView(trace) })
        }
        root.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(button(getString(R.string.crash_restart), primary = false) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            Process.killProcess(Process.myPid())
            exitProcess(0)
        })
        buttons.addView(button(getString(R.string.crash_share), primary = false) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), null))
        })
        buttons.addView(button(getString(R.string.crash_copy), primary = true) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Graphite crash", report))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        })
        root.addView(buttons)
        setContentView(root)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
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

    companion object {
        const val EXTRA_REPORT = "report"
        private val BG = Color.parseColor("#0B0B0C")
        private val SURFACE = Color.parseColor("#141416")
        private val BORDER = Color.parseColor("#2A2A2E")
        private val TEXT = Color.parseColor("#EDEDED")
        private val SECONDARY = Color.parseColor("#9A9AA0")
        private val ACCENT = Color.parseColor("#C8C8CC")
        private val ON_ACCENT = Color.parseColor("#0B0B0C")
    }
}
