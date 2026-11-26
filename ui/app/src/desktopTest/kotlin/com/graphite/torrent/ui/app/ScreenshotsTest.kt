package com.graphite.torrent.ui.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.model.TorrentState
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Renders the main screens in Russian with demo data and saves them to build/screenshots
 * (the README images come from here). Also checks that the Russian strings are in place.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotsTest {
    private lateinit var harness: TestHarness
    private val defaultLocale = Locale.getDefault()
    private val out = File("build/screenshots").apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
        harness = TestHarness()
    }

    @AfterTest
    fun tearDown() {
        harness.close()
        Locale.setDefault(defaultLocale)
    }

    private fun DesktopComposeUiTest.waitFor(matcher: SemanticsMatcher) =
        waitUntil(timeoutMillis = 5_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        val node = onAllNodes(matcher).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun DesktopComposeUiTest.save(name: String) {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val bitmap = captureToImage().asSkiaBitmap()
        val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG) ?: error("encode $name")
        File(out, "$name.png").writeBytes(png.bytes)
    }

    @Test
    fun mainScreens() = runDesktopComposeUiTest(1280, 800) {
        seedDemoData()
        harness.start()
        feedSpeedHistory()
        setContent { GraphiteApp(harness.env, showSplash = false) }

        waitFor(hasContentDescription("Sintel.2010.2160p.HDR"))
        waitFor(hasText("Загрузки"))
        save("downloads")

        click(hasContentDescription("Sintel.2010.2160p.HDR") and hasClickAction())
        waitFor(hasText("Файлы"))
        save("details")

        click(hasContentDescription("Статистика") and hasClickAction())
        waitFor(hasText("Статистика"))
        save("statistics")

        click(hasContentDescription("Настройки") and hasClickAction())
        waitFor(hasText("Нулевая отдача"))
        save("settings")

        click(hasContentDescription("Загрузки") and hasClickAction())
        click(hasContentDescription("Добавить загрузку") and hasClickAction())
        waitFor(hasSetTextAction())
        onNode(hasSetTextAction()).performTextInput("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel")
        click(hasText("Продолжить") and hasClickAction())
        waitFor(hasText("Что скачать"))
        save("add-preview")
    }

    private fun seedDemoData() {
        val e = harness.engine
        val path = DEMO_FOLDER
        val now = System.currentTimeMillis()
        val gb = 1L shl 30
        fun id(c: Char) = c.toString().repeat(40)
        e.state.update {
            mapOf(
                id('1') to e.item(id('1'), "Sintel.2010.2160p.HDR", path).copy(
                    progress = 0.62f, totalBytes = 8 * gb, wantedBytes = 8 * gb, doneBytes = (8 * gb * 0.62).toLong(),
                    downloadRate = 38_400_000, etaSeconds = 82, connectedPeers = 143, connectedSeeds = 96, totalPeers = 610,
                    totalSeeds = 512, category = "Фильмы", sequential = true, addedAt = now - 25 * 60_000,
                ),
                id('2') to e.item(id('2'), "ubuntu-24.04.1-desktop-amd64.iso", path).copy(
                    progress = 0.27f, totalBytes = 6 * gb, wantedBytes = 6 * gb, doneBytes = (6 * gb * 0.27).toLong(),
                    downloadRate = 21_700_000, etaSeconds = 215, connectedPeers = 88, connectedSeeds = 80, queuePosition = 1,
                ),
                id('3') to e.item(id('3'), "Big.Buck.Bunny.1080p.60fps", path, TorrentState.PAUSED).copy(
                    progress = 0.41f, totalBytes = 900L * 1024 * 1024, wantedBytes = 900L * 1024 * 1024,
                    doneBytes = 369L * 1024 * 1024, downloadRate = 0, etaSeconds = -1, queuePosition = 2,
                ),
                id('4') to e.item(id('4'), "debian-12.8.0-amd64-DVD-1.iso", path, TorrentState.COMPLETED).copy(
                    progress = 1f, totalBytes = 4 * gb, wantedBytes = 4 * gb, doneBytes = 4 * gb, downloadRate = 0, etaSeconds = 0,
                    addedAt = now - 26 * 3_600_000, completedAt = now - 25 * 3_600_000,
                ),
                id('5') to e.item(id('5'), "Blender.Open.Movies.Collection", path, TorrentState.ERROR).copy(
                    progress = 0.08f, totalBytes = 30 * gb, wantedBytes = 30 * gb, doneBytes = (30 * gb * 0.08).toLong(),
                    downloadRate = 0, etaSeconds = -1, error = TorrentError(ErrorKind.NO_SPACE, "D:\\"),
                ),
                id('6') to e.item(id('6'), "Elephants.Dream.2006.1080p", path, TorrentState.METADATA).copy(
                    progress = 0f, totalBytes = 0, wantedBytes = 0, doneBytes = 0, downloadRate = 0, hasMetadata = false,
                ),
            )
        }
        e.sessionState.value = SessionSnapshot(
            running = true, downloadRate = 60_100_000, activeTorrents = 3, totalTorrents = 6, dhtNodes = 412, listenPort = 51413,
        )
        runBlocking {
            val today = LocalDate.now().toEpochDay()
            for (d in 0L..29L) {
                val day = today - d
                val gbPerDay = (6 + (d * 7 % 11)) * gb
                harness.stats.addTraffic(gbPerDay, 0, 3_600L * (1 + d % 4), 40_000_000L + d * 1_000_000L, day)
                if (d % 3 == 0L) harness.stats.incrementCompleted(day)
                if (d % 2 == 0L) harness.stats.incrementAdded(day)
            }
            harness.settings.update { it.copy(defaultSavePath = DEMO_FOLDER) }
        }
    }

    /** The real-time chart samples every engine status update: replay a minute of varying speed. */
    private fun feedSpeedHistory() = runBlocking {
        val base = harness.engine.sessionState.value
        for (i in 0 until 60) {
            val rate = 45_000_000 + 15_000_000 * kotlin.math.sin(i / 6.0) + (i * 7919 % 13) * 600_000
            harness.engine.sessionState.value = base.copy(downloadRate = rate.toLong())
            kotlinx.coroutines.delay(15)
        }
        harness.engine.sessionState.value = base
    }

    private companion object {
        const val DEMO_FOLDER = "/home/user/Downloads/Graphite"
    }
}
