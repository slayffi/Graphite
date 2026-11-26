package com.graphite.torrent.core.trackers

import com.graphite.torrent.core.net.SafeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** Parsing, validation and downloading of public tracker lists. */
object TrackerList {
    private val schemes = setOf("udp", "http", "https", "ws", "wss")

    /** Returns valid announce URLs from a text list (one per line, blank lines and comments ignored). */
    fun parse(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter(::isValid)
            .distinct()
            .toList()

    fun isValid(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in schemes) return false
        val host = uri.host ?: return false
        return host.isNotBlank() && (scheme != "udp" || uri.port in 1..65535)
    }

    /** Merges lists keeping the first occurrence order and dropping duplicates (case-insensitive). */
    fun merge(vararg lists: List<String>): List<String> {
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        for (list in lists) for (url in list) {
            val key = url.trim().lowercase().removeSuffix("/")
            if (key.isNotEmpty() && seen.add(key)) result += url.trim()
        }
        return result
    }

    /**
     * Downloads all sources and merges the results. Sources that fail are skipped; throws only when
     * every source failed so the caller can keep the previous list.
     */
    suspend fun download(sources: List<String>, timeoutMs: Int = 15_000): List<String> = withContext(Dispatchers.IO) {
        val lists = ArrayList<List<String>>()
        var lastError: Exception? = null
        for (source in sources) {
            try {
                lists += parse(httpGet(source, timeoutMs))
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lists.isEmpty() && lastError != null) throw lastError
        merge(*lists.toTypedArray()).take(MAX_TRACKERS)
    }

    internal fun httpGet(url: String, timeoutMs: Int): String =
        // The source is typed in by the user, so a host in the local network is allowed.
        SafeHttp.get(url, MAX_LIST_BYTES, totalTimeoutMs = timeoutMs * 3L, allowPrivateHosts = true, connectTimeoutMs = timeoutMs)
            .toString(Charsets.UTF_8)

    private const val MAX_LIST_BYTES = 1024 * 1024

    /** More trackers than this only slow every announce down. */
    const val MAX_TRACKERS = 200
}
