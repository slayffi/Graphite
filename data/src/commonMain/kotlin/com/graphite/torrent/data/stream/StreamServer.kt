package com.graphite.torrent.data.stream

import com.graphite.torrent.core.engine.TorrentEngine
import com.graphite.torrent.data.net.HttpRequest
import com.graphite.torrent.data.net.HttpResponder
import com.graphite.torrent.data.net.MiniHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import java.security.MessageDigest

/** Byte range parsed from a "Range" header (inclusive). */
data class ByteRange(val start: Long, val end: Long)

object RangeHeader {
    /** Parses "bytes=a-b", "bytes=a-" and "bytes=-n"; null for missing/unsupported headers. */
    fun parse(header: String?, size: Long): ByteRange? {
        if (header == null || size <= 0) return null
        val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        return when {
            a.isEmpty() && b.isNotEmpty() -> {
                val n = b.toLongOrNull() ?: return null
                ByteRange((size - n).coerceAtLeast(0), size - 1)
            }
            a.isNotEmpty() -> {
                val start = a.toLongOrNull() ?: return null
                if (start >= size) return null
                val end = b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                if (end < start) null else ByteRange(start, end)
            }
            else -> null
        }
    }
}

/**
 * Serves files of torrents over HTTP on 127.0.0.1 so a player can start before the download ends.
 * Requested byte ranges are mapped to pieces that get deadlines, and the response waits for them.
 */
class StreamServer(private val engine: TorrentEngine, scope: CoroutineScope) {
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /**
     * Each file gets its own token (HMAC of the torrent id and file index): a link handed to an external player
     * opens that one file only, not everything Graphite has downloaded.
     */
    private fun tokenFor(torrentId: String, fileIndex: Int): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return mac.doFinal("$torrentId/$fileIndex".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }

    private val server = MiniHttpServer(InetAddress.getLoopbackAddress(), 0, scope, maxBody = 0) { req, res -> handle(req, res) }

    fun url(torrentId: String, fileIndex: Int, name: String): String {
        val port = server.start()
        return "http://127.0.0.1:$port/stream/${tokenFor(torrentId, fileIndex)}/$torrentId/$fileIndex/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"
    }

    fun stop() = server.stop()

    private suspend fun handle(req: HttpRequest, res: HttpResponder) {
        val parts = req.path.substringBefore('?').trim('/').split('/')
        if (parts.size < 4 || parts[0] != "stream") return res.text(404, "Not Found", "not found")
        val id = parts[2]
        val index = parts[3].toIntOrNull() ?: return res.text(404, "Not Found", "not found")
        // Constant-time comparison: the response time says nothing about how much of a guess was right.
        if (!MessageDigest.isEqual(parts[1].toByteArray(), tokenFor(id, index).toByteArray())) return res.text(404, "Not Found", "not found")
        val entry = engine.files(id).firstOrNull { it.index == index } ?: return res.text(404, "Not Found", "no such file")
        val path = engine.filePath(id, index) ?: return res.text(404, "Not Found", "no path")
        val size = entry.size
        val range = RangeHeader.parse(req.headers["range"], size)
        val start = range?.start ?: 0
        val end = range?.end ?: (size - 1)
        val headers = linkedMapOf(
            "Content-Type" to mimeFor(entry.name),
            "Accept-Ranges" to "bytes",
            "Content-Length" to (end - start + 1).coerceAtLeast(0).toString(),
            "Cache-Control" to "no-store",
        )
        if (range != null) headers["Content-Range"] = "bytes $start-$end/$size"
        res.head(if (range != null) 206 else 200, if (range != null) "Partial Content" else "OK", headers)
        if (req.method == "HEAD" || size == 0L) return res.flush()

        val lastPiece = engine.pieceSpan(id, index, size - 1, 1)?.last ?: return
        var position = start
        val buffer = ByteArray(CHUNK)
        while (position <= end) {
            val chunkEnd = minOf(end, position + CHUNK - 1)
            val span = engine.pieceSpan(id, index, position, chunkEnd - position + 1) ?: return
            if (!waitForPieces(id, span, lastPiece)) return
            val file = File(path)
            val read = RandomAccessFile(file, "r").use { raf ->
                raf.seek(position)
                raf.read(buffer, 0, (chunkEnd - position + 1).toInt())
            }
            if (read <= 0) return
            res.body(buffer, 0, read)
            position += read
        }
        res.flush()
    }

    /** Requests the pieces (plus a read-ahead window) with deadlines and waits until they are on disk. */
    private suspend fun waitForPieces(id: String, span: IntRange, lastPiece: Int): Boolean {
        if (span.all { engine.havePiece(id, it) }) {
            engine.prioritizePieces(id, (span.last + 1)..minOf(lastPiece, span.last + READ_AHEAD), 1500)
            return true
        }
        engine.prioritizePieces(id, span.first..minOf(lastPiece, span.last + READ_AHEAD), 0)
        var waited = 0L
        while (waited < TIMEOUT_MS) {
            if (span.all { engine.havePiece(id, it) }) return true
            delay(POLL_MS)
            waited += POLL_MS
        }
        return false
    }

    companion object {
        private const val CHUNK = 256 * 1024
        private const val READ_AHEAD = 12
        private const val POLL_MS = 100L
        private const val TIMEOUT_MS = 120_000L

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
