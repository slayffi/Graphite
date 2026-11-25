package com.graphite.torrent.core.engine

import org.libtorrent4j.LibTorrent
import java.io.File
import java.io.IOException
import java.security.MessageDigest

actual object NativeLibraries {
    private const val PROPERTY = "libtorrent4j.jni.path"

    @Volatile
    private var loaded = false

    actual fun ensureLoaded(extractDir: String?) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            if (System.getProperty(PROPERTY).isNullOrEmpty()) {
                val target = extract(extractDir)
                if (target != null) System.setProperty(PROPERTY, target.absolutePath)
            }
            LibTorrent.version()
            loaded = true
        }
    }

    /** Candidate resource paths inside the libtorrent4j native jars for this OS/CPU. */
    internal fun resourceCandidates(osName: String, arch: String): List<String> {
        val os = osName.lowercase()
        val isArm = arch.lowercase().let { it.contains("aarch64") || it.contains("arm") }
        return when {
            os.contains("win") -> listOf("lib/x86_64/libtorrent4j.dll")
            os.contains("mac") || os.contains("darwin") ->
                if (isArm) listOf("lib/arm64/libtorrent4j.dylib", "lib/x86_64/libtorrent4j.dylib")
                else listOf("lib/x86_64/libtorrent4j.dylib", "lib/arm64/libtorrent4j.dylib")
            else ->
                if (isArm) listOf("lib/arm/libtorrent4j.so", "lib/arm64/libtorrent4j.so")
                else listOf("lib/x86_64/libtorrent4j.so")
        }
    }

    private fun extract(extractDir: String?): File? {
        val loader = Thread.currentThread().contextClassLoader ?: NativeLibraries::class.java.classLoader
        val candidates = resourceCandidates(System.getProperty("os.name"), System.getProperty("os.arch"))
        for (resource in candidates) {
            val bytes = loader.getResourceAsStream(resource)?.use { it.readBytes() } ?: continue
            val dir = File(extractDir ?: File(System.getProperty("java.io.tmpdir"), "graphite-native").path)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }.take(16)
            val file = File(dir, "$digest-${resource.substringAfterLast('/')}")
            if (!file.isFile || file.length() != bytes.size.toLong()) {
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    if (!tmp.renameTo(file)) throw IOException("Cannot write $file")
                }
            }
            return file
        }
        return null
    }
}
