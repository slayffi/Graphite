package com.graphite.torrent.ui.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.graphite.torrent.ui.designsystem.platform.PlatformKind
import com.graphite.torrent.ui.designsystem.platform.platformOverride
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The phone layout (bottom bar, splash) is what Android shows; the other UI tests use the desktop one. */
@OptIn(ExperimentalTestApi::class)
class PhoneLayoutTest {
    private lateinit var harness: TestHarness
    private val defaultLocale = Locale.getDefault()

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
        platformOverride = PlatformKind.ANDROID
        harness = TestHarness()
    }

    @AfterTest
    fun tearDown() {
        harness.close()
        platformOverride = null
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun phoneStartShowsTheListAfterTheSplash() = runDesktopComposeUiTest(412, 915) {
        harness.start()
        setContent { GraphiteApp(harness.env) }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Добавьте первую загрузку")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }
}
