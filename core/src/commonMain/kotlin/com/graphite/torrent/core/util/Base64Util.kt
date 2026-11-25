package com.graphite.torrent.core.util

import java.util.Base64

object Base64Util {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
    fun encodeUrl(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decodeUrl(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}
