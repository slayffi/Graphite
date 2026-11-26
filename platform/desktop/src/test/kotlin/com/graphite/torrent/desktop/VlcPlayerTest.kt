package com.graphite.torrent.desktop

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test of the libVLC binding: decodes a short generated clip through the RV32 callbacks.
 * Skipped when libVLC or ffmpeg is not installed on the build machine (CI runners do not have them).
 */
class VlcPlayerTest {
    @Test
    fun decodesFramesThroughCallbacks() {
        assumeTrue("libVLC is not installed", LibVlcLoader.api != null)
        val dir = Files.createTempDirectory("vlc-test").toFile()
        val clip = File(dir, "clip.mp4")
        val ffmpeg = runCatching {
            ProcessBuilder(
                "ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=3",
                "-pix_fmt", "yuv420p", clip.absolutePath,
            ).redirectErrorStream(true).start().waitFor()
        }.getOrDefault(-1)
        assumeTrue("ffmpeg is not installed", ffmpeg == 0 && clip.isFile)

        val player = VlcPlayer()
        try {
            assertTrue(player.state.value.available, "player should be available")
            player.setVolume(0f)
            player.open(clip.toURI().toString().replace("file:/", "file:///"))
            player.play()
            val deadline = System.currentTimeMillis() + 15_000
            while (player.renderedFrames.get() < 10 && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(player.renderedFrames.get() >= 10, "frames rendered: ${player.renderedFrames.get()}")
            assertTrue(player.videoSize == 320 to 240, "decoded size ${player.videoSize}")
            Thread.sleep(600)
            val state = player.state.value
            assertTrue(state.durationMs in 2_500..3_500, "duration ${state.durationMs}")
            assertTrue(state.error == null, "error ${state.error}")
        } finally {
            player.release()
            dir.deleteRecursively()
        }
    }
}
