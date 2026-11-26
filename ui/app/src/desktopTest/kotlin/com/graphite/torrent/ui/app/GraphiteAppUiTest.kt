package com.graphite.torrent.ui.app

import androidx.compose.ui.test.ComposeTimeoutException
import com.graphite.torrent.ui.app.details.DetailsViewModel
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.graphite.torrent.core.model.TorrentState
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Key user scenarios on the real [GraphiteApp] (desktop layout, English strings, fake engine). */
@OptIn(ExperimentalTestApi::class)
class GraphiteAppUiTest {
    private lateinit var harness: TestHarness
    private val defaultLocale = Locale.getDefault()

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        harness = TestHarness()
    }

    @AfterTest
    fun tearDown() {
        harness.close()
        Locale.setDefault(defaultLocale)
    }

    private fun ComposeUiTest.launchApp() {
        harness.start()
        setContent { GraphiteApp(harness.env, showSplash = false) }
        waitForIdle()
    }

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 5_000) {
        try {
            waitUntil(timeoutMillis = timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            // Show what is on screen instead of a bare timeout.
            throw AssertionError("Not found: ${matcher.description}\n" + onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE), e)
        }
    }

    private fun ComposeUiTest.await(what: String, condition: () -> Boolean) {
        try {
            waitUntil(timeoutMillis = 5_000) { condition() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Timed out: $what\n" + onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE), e)
        }
    }

    private fun ComposeUiTest.click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        val node = onAllNodes(matcher).onFirst()
        // Buttons at the end of long sheets are below the fold: scroll them into view first.
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    @Test
    fun emptyStateAddMagnetThroughPreview() = runDesktopComposeUiTest(1280, 800) {
        launchApp()
        waitFor(hasText("Add your first download"))

        click(hasText("Add download") and hasClickAction())
        waitFor(hasSetTextAction())
        onNode(hasSetTextAction()).performTextInput(MAGNET)
        click(hasText("Continue") and hasClickAction())

        // The fake engine "downloads" metadata instantly: the preview lists the files.
        waitFor(hasText("What to download"))
        click(hasText("Download") and hasClickAction())

        await("torrent added to the engine") { harness.engine.state.value.containsKey(HASH) }
        waitFor(hasContentDescription("meta"))
        waitFor(hasText("Added: meta"))
        assertEquals(harness.downloads.absolutePath, harness.engine.lastSavePath)
    }

    @Test
    fun invalidLinkShowsHumanReadableError() = runDesktopComposeUiTest(1280, 800) {
        launchApp()
        click(hasText("Add download") and hasClickAction())
        waitFor(hasSetTextAction())
        onNode(hasSetTextAction()).performTextInput("definitely not a magnet link")
        click(hasText("Continue") and hasClickAction())
        waitFor(hasText("The torrent is damaged or the link is wrong"))
        assertTrue(harness.engine.state.value.isEmpty())
    }

    @Test
    fun notEnoughSpaceIsExplained() = runDesktopComposeUiTest(1280, 800) {
        harness.close()
        harness = TestHarness(freeSpace = 100)
        launchApp()
        click(hasText("Add download") and hasClickAction())
        waitFor(hasSetTextAction())
        onNode(hasSetTextAction()).performTextInput(MAGNET)
        click(hasText("Continue") and hasClickAction())

        // The preview already warns, and adding fails with a human readable reason and advice.
        waitFor(hasText("Needs 1000 B, only 100 B free"))
        click(hasText("Download") and hasClickAction())
        waitFor(hasText("Not enough disk space"))
        waitFor(hasText("Free up space or choose another folder."))
        assertTrue(harness.engine.state.value.isEmpty())
    }

    @Test
    fun filtersAndPauseFromSelection() = runDesktopComposeUiTest(1280, 800) {
        seed()
        launchApp()
        waitFor(hasContentDescription("Alpha"))
        waitFor(hasContentDescription("Beta"))

        click(hasText("Paused") and hasClickAction())
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Alpha")).fetchSemanticsNodes().isEmpty() }
        onAllNodes(hasContentDescription("Beta")).assertCountEquals(1)

        click(hasText("All") and hasClickAction())
        waitFor(hasContentDescription("Alpha"))
        onAllNodes(hasContentDescription("Alpha") and hasClickAction()).onFirst().performTouchInput { longClick() }
        click(hasContentDescription("Pause") and hasClickAction())
        waitUntil(timeoutMillis = 5_000) { harness.engine.state.value.getValue(ALPHA).state == TorrentState.PAUSED }
    }

    @Test
    fun closedDetailsPageStopsAskingTheEngine() = runDesktopComposeUiTest(1280, 800) {
        seed()
        launchApp()
        click(hasContentDescription("Alpha") and hasClickAction())
        click(hasText("Peers") and hasClickAction())
        waitUntil(timeoutMillis = 5_000) { (harness.engine.peerRequests[ALPHA] ?: 0) > 0 }

        // Another download is opened: the first page is gone and must not keep polling for as long as the app runs.
        click(hasContentDescription("Beta") and hasClickAction())
        click(hasText("Peers") and hasClickAction())
        waitUntil(timeoutMillis = 5_000) { (harness.engine.peerRequests[BETA] ?: 0) > 0 }
        Thread.sleep(500)
        val before = harness.engine.peerRequests[ALPHA]
        val betaBefore = harness.engine.peerRequests[BETA] ?: 0
        Thread.sleep(2 * DetailsViewModel.POLL_MS + 500)
        waitForIdle()
        assertEquals(before, harness.engine.peerRequests[ALPHA], "the closed page still polls")
        assertTrue((harness.engine.peerRequests[BETA] ?: 0) > betaBefore, "the open page must keep refreshing")
    }

    @Test
    fun deleteAsksForConfirmation() = runDesktopComposeUiTest(1280, 800) {
        seed()
        launchApp()
        waitFor(hasContentDescription("Beta"))
        onAllNodes(hasContentDescription("Beta") and hasClickAction()).onFirst().performTouchInput { longClick() }
        click(hasContentDescription("Delete") and hasClickAction())

        // Nothing is removed before the user confirms.
        waitFor(hasText("Delete, keep files"))
        assertTrue(harness.engine.state.value.containsKey(BETA))
        click(hasText("Delete, keep files") and hasClickAction())
        waitUntil(timeoutMillis = 5_000) { !harness.engine.state.value.containsKey(BETA) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Beta")).fetchSemanticsNodes().isEmpty() }
        assertTrue(harness.engine.state.value.containsKey(ALPHA))
    }

    @Test
    fun settingsSearchAndZeroUploadSwitch() = runDesktopComposeUiTest(1280, 800) {
        launchApp()
        click((hasContentDescription("Settings") or hasText("Settings")) and hasClickAction())
        val search = hasSetTextAction() and hasText("Search settings")
        waitFor(search)
        onNode(search).performTextInput("zero")
        waitFor(hasText("Zero upload"))
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Download folder").fetchSemanticsNodes().isEmpty() }

        assertTrue(runBlocking { harness.settings.current() }.zeroUpload)
        click(hasText("Zero upload") and hasClickAction())
        waitUntil(timeoutMillis = 5_000) { !harness.settings.settings.value.zeroUpload }
        // The engine gets the new configuration right away.
        waitUntil(timeoutMillis = 5_000) { harness.engine.lastConfig?.settings?.zeroUpload == false }
        assertFalse(harness.settings.settings.value.zeroUpload)
    }

    private fun seed() {
        harness.engine.state.update {
            mapOf(
                ALPHA to harness.engine.item(ALPHA, "Alpha", harness.downloads.absolutePath),
                BETA to harness.engine.item(BETA, "Beta", harness.downloads.absolutePath, TorrentState.PAUSED),
            )
        }
    }

    private companion object {
        const val HASH = "c9e15763f722f23e98a29decdfae341b98d53056"
        const val MAGNET = "magnet:?xt=urn:btih:$HASH&dn=Sintel"
        val ALPHA = "a".repeat(40)
        val BETA = "b".repeat(40)
    }
}
