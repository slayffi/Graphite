package com.graphite.torrent.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MagnetLinkTest {
    private val hash = "c9e15763f722f23e98a29decdfae341b98d53056"

    @Test
    fun parsesHexMagnet() {
        val m = assertNotNull(MagnetLink.parse("magnet:?xt=urn:btih:${hash.uppercase()}&dn=Big+Buck%20Bunny&tr=udp%3A%2F%2Ftracker.example%3A80"))
        assertEquals(hash, m.infoHashV1)
        assertEquals("Big Buck Bunny", m.displayName)
        assertEquals(listOf("udp://tracker.example:80"), m.trackers)
        assertEquals(hash, m.id)
    }

    @Test
    fun parsesBase32Magnet() {
        val m = assertNotNull(MagnetLink.parse("magnet:?xt=urn:btih:ZHQVOY7XELZD5GFCTXWN7LRUDOMNKMCW"))
        assertEquals(hash, m.infoHashV1)
    }

    @Test
    fun parsesV2Magnet() {
        val v2 = "a".repeat(64)
        val m = assertNotNull(MagnetLink.parse("magnet:?xt=urn:btmh:1220$v2"))
        assertNull(m.infoHashV1)
        assertEquals(v2, m.infoHashV2)
        assertEquals(v2.take(40), m.id)
    }

    @Test
    fun rejectsInvalid() {
        assertNull(MagnetLink.parse("https://example.com"))
        assertNull(MagnetLink.parse("magnet:?dn=nothing"))
        assertNull(MagnetLink.parse("magnet:?xt=urn:btih:123"))
    }

    @Test
    fun findsLinkInText() {
        val text = "look at this: magnet:?xt=urn:btih:$hash&dn=x and more"
        assertEquals("magnet:?xt=urn:btih:$hash&dn=x", MagnetLink.findInText(text))
        assertEquals("magnet:?xt=urn:btih:$hash", MagnetLink.findInText("hash ${hash.uppercase()} here"))
        assertNull(MagnetLink.findInText("nothing here"))
    }
}
