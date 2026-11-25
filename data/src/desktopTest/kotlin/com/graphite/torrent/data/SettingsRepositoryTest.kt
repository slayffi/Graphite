package com.graphite.torrent.data

import com.graphite.torrent.core.settings.SpeedProfile
import com.graphite.torrent.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsRepositoryTest {
    @Test
    fun defaultsPersistAndSurviveRestart() = runBlocking {
        val dir = createTempDirectory("settings").toFile()
        val file = File(dir, "settings.json")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SettingsRepository.create(file.absolutePath, scope1)
        val defaults = repo.current()
        assertEquals(SpeedProfile.TURBO, defaults.profile)
        assertTrue(defaults.zeroUpload)
        assertEquals(0, defaults.downloadLimitKbps)
        repo.update { it.copy(profile = SpeedProfile.ECONOMY, listenPort = 40000) }
        scope1.coroutineContext[Job]!!.cancelAndJoin()

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val again = SettingsRepository.create(file.absolutePath, scope2).current()
        assertEquals(SpeedProfile.ECONOMY, again.profile)
        assertEquals(40000, again.listenPort)
        scope2.cancel()
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun corruptedFileFallsBackToDefaultsAndIgnoresUnknownKeys() = runBlocking {
        val dir = createTempDirectory("settings").toFile()
        val broken = File(dir, "broken.json").apply { writeText("{not json") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        assertEquals(SpeedProfile.TURBO, SettingsRepository.create(broken.absolutePath, scope).current().profile)

        val future = File(dir, "future.json").apply { writeText("""{"profile":"BALANCED","someFutureOption":42}""") }
        assertEquals(SpeedProfile.BALANCED, SettingsRepository.create(future.absolutePath, scope).current().profile)
        scope.cancel()
        dir.deleteRecursively()
        Unit
    }
}
