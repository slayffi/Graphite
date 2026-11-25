package com.graphite.torrent.core.engine

/** A blocked address range (inclusive). Addresses are normalized textual IPv4/IPv6 addresses. */
data class IpRange(val first: String, val last: String)

/**
 * Parses common block list formats:
 * - eMule DAT: `001.002.003.000 - 001.002.003.255 , 000 , description` (access level < 128 = blocked)
 * - PeerGuardian P2P: `description:1.2.3.0-1.2.3.255`
 * - CIDR: `1.2.3.0/24`
 * - single address or `first - last` ranges (IPv4 and IPv6)
 */
object IpFilterParser {
    private val V4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun parse(lines: Sequence<String>): List<IpRange> {
        val result = ArrayList<IpRange>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            parseLine(line)?.let { result += it }
        }
        return result
    }

    fun parseLine(line: String): IpRange? {
        // eMule DAT
        if (line.count { it == ',' } >= 2) {
            val parts = line.split(',')
            val level = parts[1].trim().toIntOrNull() ?: 0
            if (level >= 128) return null
            return parseRange(parts[0].trim())
        }
        // P2P: "name:a-b" (name may contain ':'), but IPv6 ranges contain ':' too.
        val rangePart = if (line.contains('-') && !looksLikeIpv6Range(line)) line.substringAfterLast(':') else line
        return parseRange(rangePart.trim())
    }

    private fun looksLikeIpv6Range(line: String): Boolean {
        val dash = line.indexOf('-')
        if (dash < 0) return false
        val left = line.substring(0, dash).trim()
        return left.count { it == ':' } >= 2 && left.all { it.isLetterOrDigit() || it == ':' || it == '.' }
    }

    private fun parseRange(text: String): IpRange? {
        if (text.contains('/')) return parseCidr(text)
        val dash = text.indexOf('-')
        if (dash > 0) {
            val a = normalize(text.substring(0, dash).trim()) ?: return null
            val b = normalize(text.substring(dash + 1).trim()) ?: return null
            if (isV4(a) != isV4(b)) return null
            return IpRange(a, b)
        }
        val single = normalize(text) ?: return null
        return IpRange(single, single)
    }

    private fun parseCidr(text: String): IpRange? {
        val (addr, bitsText) = text.split('/', limit = 2).let { it[0].trim() to it[1].trim() }
        val ip = normalize(addr) ?: return null
        if (!isV4(ip)) return null
        val bits = bitsText.toIntOrNull()?.takeIf { it in 0..32 } ?: return null
        val value = ip.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        val first = value and mask
        val last = first or (mask.inv() and 0xFFFFFFFFL)
        return IpRange(toV4(first), toV4(last))
    }

    private fun toV4(v: Long) = "${v shr 24 and 255}.${v shr 16 and 255}.${v shr 8 and 255}.${v and 255}"

    private fun isV4(s: String) = V4.matches(s)

    /** Strips leading zeros from IPv4 octets ("001.002.003.004") and validates the address. */
    fun normalize(text: String): String? {
        if (V4.matches(text)) {
            val parts = text.split('.').map { it.toInt() }
            if (parts.any { it > 255 }) return null
            return parts.joinToString(".")
        }
        if (text.contains(':') && text.all { it.isLetterOrDigit() || it == ':' || it == '.' }) {
            return text.lowercase()
        }
        return null
    }
}
