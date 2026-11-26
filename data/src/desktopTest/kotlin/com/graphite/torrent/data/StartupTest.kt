package com.graphite.torrent.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Android any exception that escapes a background coroutine kills the app, while on the desktop it
 * is only printed. This runs the real services the way the Android app starts them (engine started
 * from two places at once, automation on) and fails on any uncaught exception.
 */
class StartupTest {
    @Test
    fun androidLikeStartupHasNoUncaughtExceptions() = runBlocking {
        val dir = createTempDirectory("startup").toFile()
        val errors = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> errors += e }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val services = GraphiteServices.create(
                dataDir = File(dir, "data"),
                driver = DatabaseFactory.createInMemory(),
                storage = FileSystemStorage(File(dir, "downloads").absolutePath),
                scope = scope,
            )
            // GraphiteApplication.onCreate and MainActivity.onResume both start the engine.
            listOf(
                scope.launch { services.controller.start(); services.startAutomation() },
                scope.launch { services.controller.start(); services.startAutomation() },
            ).joinAll()
            delay(4_000)
            assertTrue(services.controller.session.value.running, "engine is running")
            services.stopAutomation()
            services.controller.stop()
            assertEquals(emptyList(), errors.map { it.toString() })
        } finally {
            scope.cancel()
            Thread.setDefaultUncaughtExceptionHandler(previous)
            dir.deleteRecursively()
        }
    }
}
