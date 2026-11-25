package com.graphite.torrent.core.util

import java.net.URLDecoder

/** Lightweight magnet URI parser used for validation and for extracting links from free text. */
data class MagnetLink(
    /** Lower-case hex v1 info hash (40 chars) if present. */
    val infoHashV1: String?,
    /** Lower-case hex v2 multihash payload (64 chars) if present. */
    val infoHashV2: String?,
    val displayName: String?,
    val trackers: List<String>,
    val webSeeds: List<String>,
    val raw: String,
) {
    /** Identifier compatible with the engine ids (v1 when present, otherwise truncated v2). */
    val id: String get() = infoHashV1 ?: infoHashV2!!.take(40)

    companion object {
        private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
        private val BASE32_32 = Regex("^[A-Za-z2-7]{32}$")
        private val MAGNET_IN_TEXT = Regex("""magnet:\?[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        private val BARE_HASH_IN_TEXT = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{40}(?![0-9a-fA-F])""")

        fun isMagnet(text: String): Boolean = parse(text) != null

        /** Parses a magnet URI. Returns null when it is not a valid BitTorrent magnet link. */
        fun parse(uri: String): MagnetLink? {
            val trimmed = uri.trim()
            if (!trimmed.startsWith("magnet:?", ignoreCase = true)) return null
            val query = trimmed.substring(8)
            var v1: String? = null
            var v2: String? = null
            var name: String? = null
            val trackers = ArrayList<String>()
            val seeds = ArrayList<String>()
            for (part in query.split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).lowercase().substringBefore('.')
                val value = decode(part.substring(eq + 1))
                when (key) {
                    "xt" -> when {
                        value.startsWith("urn:btih:", ignoreCase = true) -> {
                            val h = value.substring(9)
                            v1 = when {
                                HEX40.matches(h) -> h.lowercase()
                                BASE32_32.matches(h) -> base32ToHex(h)
                                else -> v1
                            }
                        }
                        value.startsWith("urn:btmh:", ignoreCase = true) -> {
                            val h = value.substring(9).lowercase()
                            // multihash: 0x12 (sha2-256) 0x20 (32 bytes) + 64 hex digits
                            if (h.length == 68 && h.startsWith("1220") && h.drop(4).all { it.isHexDigit() }) v2 = h.drop(4)
                        }
                    }
                    "dn" -> name = value
                    "tr" -> if (value.isNotBlank()) trackers += value
                    "ws" -> if (value.isNotBlank()) seeds += value
                }
            }
            if (v1 == null && v2 == null) return null
            return MagnetLink(v1, v2, name, trackers.distinct(), seeds.distinct(), trimmed)
        }

        /**
         * Finds the first magnet link in arbitrary text (clipboard, shared text). A bare 40-character
         * info hash is converted into a magnet link as well.
         */
        fun findInText(text: String): String? {
            MAGNET_IN_TEXT.find(text)?.value?.let { candidate ->
                if (parse(candidate) != null) return candidate
            }
            BARE_HASH_IN_TEXT.find(text)?.value?.let { return "magnet:?xt=urn:btih:${it.lowercase()}" }
            return null
        }

        private fun decode(s: String): String =
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        internal fun base32ToHex(input: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val bytes = ByteArray(20)
            var buffer = 0
            var bits = 0
            var index = 0
            for (c in input.uppercase()) {
                buffer = (buffer shl 5) or alphabet.indexOf(c)
                bits += 5
                if (bits >= 8) {
                    bytes[index++] = (buffer shr (bits - 8) and 0xFF).toByte()
                    bits -= 8
                }
            }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
