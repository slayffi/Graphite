package com.graphite.torrent.core.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/** Builds small single-file v1 torrents for tests (bencoding written by hand). */
object TestTorrents {
    fun createPayload(dir: File, name: String, size: Int, seed: Int = 42): File {
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(Random(seed).nextBytes(size))
        return file
    }

    fun torrentFor(file: File, pieceLength: Int = 256 * 1024, private: Boolean = false): ByteArray {
        val data = file.readBytes()
        val pieces = ByteArrayOutputStream()
        val sha1 = MessageDigest.getInstance("SHA-1")
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + pieceLength, data.size)
            sha1.reset()
            sha1.update(data, offset, end - offset)
            pieces.write(sha1.digest())
            offset = end
        }
        val info = sortedMapOf<String, Any>(
            "length" to data.size.toLong(),
            "name" to file.name,
            "piece length" to pieceLength.toLong(),
            "pieces" to pieces.toByteArray(),
        )
        if (private) info["private"] = 1L
        val root = sortedMapOf<String, Any>(
            "created by" to "Graphite tests",
            "creation date" to 1_700_000_000L,
            "comment" to "test torrent",
            "info" to info,
        )
        return bencode(root)
    }

    fun bencode(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is Long -> out.write("i${value}e".toByteArray())
            is Int -> out.write("i${value}e".toByteArray())
            is String -> value.toByteArray(Charsets.UTF_8).let { out.write("${it.size}:".toByteArray()); out.write(it) }
            is ByteArray -> { out.write("${value.size}:".toByteArray()); out.write(value) }
            is List<*> -> { out.write('l'.code); value.forEach { write(out, it!!) }; out.write('e'.code) }
            is Map<*, *> -> {
                out.write('d'.code)
                value.entries.sortedBy { it.key as String }.forEach { (k, v) -> write(out, k as String); write(out, v!!) }
                out.write('e'.code)
            }
            else -> error("unsupported ${value::class}")
        }
    }
}
