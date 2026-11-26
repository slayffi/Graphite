package com.graphite.torrent.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.graphite.torrent.data.GraphiteServices
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * `Graphite.exe --self-test <report file>`: an ordinary start in a throw-away data folder that ends by itself
 * and says whether it worked (exit code 0 and "RESULT: PASSED" in the report).
 *
 * CI runs it on the installed package. A package can be broken in ways no test of the code sees: a Java module
 * missing from the bundled runtime, a native library that does not load on a clean Windows, a renderer that
 * cannot start. The user's downloads and settings are not touched.
 */
internal class SelfTest(private val reportFile: File) {
    private val errors = CopyOnWriteArrayList<String>()

    /** The real screens are on display (the start animation is over). */
    @Volatile var contentShown = false

    /** Must run before anything reads [DesktopPaths]. */
    fun prepare() {
        DesktopPaths.dataRoot = Files.createTempDirectory("graphite-self-test").toFile()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            errors += "Uncaught in thread \"${t.name}\":\n${e.stackTraceToString()}"
            previous?.uncaughtException(t, e)
        }
    }

    fun watch(services: GraphiteServices, frame: () -> Frame?) {
        val reported = services.backgroundErrors.sink
        services.backgroundErrors.sink = { e ->
            errors += "Background job failed:\n${e.stackTraceToString()}"
            reported(e)
        }
        thread(name = "graphite-self-test", isDaemon = true) {
            val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
            var window = false
            var engine = false
            while (System.nanoTime() < deadline) {
                window = frame()?.isShowing == true
                engine = services.controller.session.value.running
                if (window && engine && contentShown) break
                Thread.sleep(250)
            }
            val passed = window && engine && contentShown && errors.isEmpty()
            val vlc = runCatching { LibVlcLoader.api != null }.getOrDefault(false)
            val text = buildString {
                appendLine("Graphite ${System.getProperty("graphite.version") ?: "(run from source)"} self-test")
                appendLine("Java ${System.getProperty("java.version")}, ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
                appendLine(line(window, "window shown" + ((frame() as? ComposeWindow)?.let { ", renderer ${runCatching { it.renderApi }.getOrNull()}" } ?: "")))
                appendLine(line(contentShown, "interface drawn"))
                appendLine(line(engine, "torrent engine running" + if (engine) ", port ${services.controller.session.value.listenPort}" else ""))
                appendLine(if (vlc) "OK    player: libVLC found" else "NOTE  player: libVLC not found, videos open in another app")
                errors.forEach { appendLine().appendLine(it.trimEnd()) }
                appendLine("RESULT: ${if (passed) "PASSED" else "FAILED"}")
            }
            runCatching { reportFile.absoluteFile.parentFile?.mkdirs(); reportFile.writeText(text) }
            print(text)
            runCatching { runBlocking { withTimeoutOrNull(15_000) { services.controller.stop() } } }
            runCatching { DesktopPaths.dataRoot?.deleteRecursively() }
            exitProcess(if (passed) 0 else 1)
        }
    }

    private fun line(ok: Boolean, what: String) = (if (ok) "OK    " else "FAIL  ") + what

    companion object {
        const val FLAG = "--self-test"
        private const val TIMEOUT_MS = 120_000L

        fun fromArguments(args: Array<String>): SelfTest? {
            val at = args.indexOf(FLAG)
            if (at < 0) return null
            return SelfTest(File(args.getOrNull(at + 1) ?: "graphite-self-test.txt"))
        }
    }
}
