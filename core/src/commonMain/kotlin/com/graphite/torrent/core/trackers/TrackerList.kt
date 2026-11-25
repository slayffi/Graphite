package com.graphite.torrent.core.trackers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
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
        merge(*lists.toTypedArray())
    }

    internal fun httpGet(url: String, timeoutMs: Int): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Graphite")
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code for $url")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}
