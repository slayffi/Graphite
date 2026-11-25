package com.graphite.torrent.core.engine

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileResumeStoreTest {
    @Test
    fun saveLoadDeleteQuarantine() {
        val dir = Files.createTempDirectory("resume").toFile()
        val store = FileResumeStore(dir)
        store.save("abc", byteArrayOf(1, 2, 3))
        store.save("abc", byteArrayOf(4, 5))
        store.save("def", byteArrayOf(9))
        val all = store.loadAll()
        assertEquals(setOf("abc", "def"), all.keys)
        assertContentEquals(byteArrayOf(4, 5), all["abc"])
        store.delete("def")
        assertEquals(setOf("abc"), store.loadAll().keys)
        store.quarantine("abc")
        assertTrue(store.loadAll().isEmpty())
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".broken") })
        dir.deleteRecursively()
    }
}
