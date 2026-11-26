package com.graphite.torrent.core.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

/** Reads that cannot exhaust memory or wait forever: everything that comes from outside goes through here. */
object BoundedIo {
    /** Real .torrent files are kilobytes to a few megabytes; anything beyond this is not a torrent. */
    const val MAX_TORRENT_BYTES = 32 * 1024 * 1024

    /**
     * Reads the whole stream. Fails as soon as it is larger than [limit] bytes or [deadlineMs]
     * (a [System.currentTimeMillis] value) has passed, instead of growing until memory runs out.
     */
    fun read(input: InputStream, limit: Int, deadlineMs: Long = Long.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) throw IOException("larger than $limit bytes")
            if (System.currentTimeMillis() > deadlineMs) throw SocketTimeoutException("the transfer took too long")
            out.write(buffer, 0, n)
        }
    }

    fun readFile(file: File, limit: Int): ByteArray {
        if (file.length() > limit) throw IOException("${file.name} is larger than $limit bytes")
        return file.inputStream().use { read(it, limit) }
    }
}

/** HTTP GET for feeds, tracker lists and .torrent links: size cap, overall time limit, redirects checked hop by hop. */
object SafeHttp {
    private const val MAX_REDIRECTS = 5

    /**
     * @param allowPrivateHosts false for URLs that came from somewhere else (links inside a feed): they must not
     * make the app send requests into the local network or to the computer itself.
     */
    fun get(
        url: String,
        maxBytes: Int,
        totalTimeoutMs: Long = 60_000,
        allowPrivateHosts: Boolean = false,
        connectTimeoutMs: Int = 15_000,
    ): ByteArray {
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        var current = url.trim()
        for (hop in 0..MAX_REDIRECTS) {
            val uri = URI(current)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") throw IOException("unsupported address: $current")
            val host = uri.host ?: throw IOException("no host in $current")
            if (!allowPrivateHosts && isPrivateHost(host)) throw IOException("$host is a local address")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Graphite")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $code without a target")
                    current = uri.resolve(location).toString()
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code for $current")
                return connection.inputStream.use { BoundedIo.read(it, maxBytes, deadline) }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects for $url")
    }

    /** True when the name resolves to this computer or to a private network. Unknown hosts count as public. */
    fun isPrivateHost(host: String): Boolean = runCatching {
        InetAddress.getAllByName(host).any(::isPrivateAddress)
    }.getOrDefault(false)

    fun isPrivateAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isAnyLocalAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            // IPv6 unique local addresses fc00::/7
            (address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC)
}
