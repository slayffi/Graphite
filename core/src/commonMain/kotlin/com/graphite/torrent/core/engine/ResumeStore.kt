package com.graphite.torrent.core.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Persists libtorrent resume data so downloads survive restarts and crashes. */
interface ResumeStore {
    fun save(id: String, data: ByteArray)
    fun delete(id: String)
    fun loadAll(): Map<String, ByteArray>

    /** Moves an unreadable entry aside so it is not retried on every start. */
    fun quarantine(id: String)
}

/**
 * One file per torrent: `<dir>/<id>.resume`. Writes go to a temporary file first and are then
 * atomically renamed, so a crash in the middle of a write never corrupts the previous copy.
 */
class FileResumeStore(private val dir: File) : ResumeStore {
    init {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create resume directory $dir")
    }

    private fun fileFor(id: String) = File(dir, "${sanitize(id)}.resume")

    @Synchronized
    override fun save(id: String, data: ByteArray) {
        val target = fileFor(id)
        val tmp = File(dir, "${sanitize(id)}.resume.tmp")
        tmp.outputStream().use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    override fun delete(id: String) {
        fileFor(id).delete()
        File(dir, "${sanitize(id)}.resume.tmp").delete()
    }

    @Synchronized
    override fun loadAll(): Map<String, ByteArray> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".resume") } ?: return emptyMap()
        val result = LinkedHashMap<String, ByteArray>()
        for (f in files.sortedBy { it.lastModified() }) {
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            if (bytes.isNotEmpty()) result[f.name.removeSuffix(".resume")] = bytes
        }
        return result
    }

    @Synchronized
    override fun quarantine(id: String) {
        val f = fileFor(id)
        if (f.exists()) f.renameTo(File(dir, "${sanitize(id)}.broken"))
    }

    private fun sanitize(id: String) = id.filter { it.isLetterOrDigit() }.ifEmpty { "unknown" }
}
