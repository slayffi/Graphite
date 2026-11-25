package com.graphite.torrent.core.trackers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerListTest {
    @Test
    fun parsesAndValidates() {
        val text = """
            udp://tracker.opentrackr.org:1337/announce

            # comment
            https://tracker.example.org/announce
            udp://no-port.example.org/announce
            ftp://bad.example/announce
            udp://tracker.opentrackr.org:1337/announce
        """.trimIndent()
        assertEquals(
            listOf("udp://tracker.opentrackr.org:1337/announce", "https://tracker.example.org/announce"),
            TrackerList.parse(text),
        )
    }

    @Test
    fun validation() {
        assertTrue(TrackerList.isValid("wss://tracker.webtorrent.dev"))
        assertFalse(TrackerList.isValid("not a url"))
        assertFalse(TrackerList.isValid("udp://host"))
    }

    @Test
    fun mergeDropsDuplicatesCaseInsensitive() {
        val merged = TrackerList.merge(listOf("udp://A.org:1/announce"), listOf("udp://a.org:1/announce", "http://b.org/announce"))
        assertEquals(listOf("udp://A.org:1/announce", "http://b.org/announce"), merged)
    }
}
