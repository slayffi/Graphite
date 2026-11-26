package com.graphite.torrent.core.rss

import com.graphite.torrent.core.model.RssRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RssParserTest {
    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:torrent="http://xmlns.ezrss.it/0.1/">
          <channel>
            <title>Series feed</title>
            <item>
              <title>Show S02E07 1080p</title>
              <guid>ep-7</guid>
              <pubDate>Mon, 23 Nov 2026 10:00:00 +0000</pubDate>
              <enclosure url="https://example.org/ep7.torrent" length="123456" type="application/x-bittorrent"/>
            </item>
            <item>
              <title>Show S02E08 720p</title>
              <link>https://example.org/page</link>
              <torrent:magnetURI><![CDATA[magnet:?xt=urn:btih:c9e15763f722f23e98a29decdfae341b98d53056]]></torrent:magnetURI>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private val atom = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Atom feed</title>
          <entry>
            <id>urn:1</id>
            <title>Album FLAC</title>
            <updated>2026-11-20T08:00:00Z</updated>
            <link rel="alternate" href="https://example.org/album"/>
            <link rel="enclosure" type="application/x-bittorrent" href="https://example.org/album.torrent" length="999"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parsesRss2() {
        val feed = RssParser.parse(rss.toByteArray())
        assertEquals("Series feed", feed.title)
        assertEquals(2, feed.items.size)
        assertEquals("https://example.org/ep7.torrent", feed.items[0].link)
        assertEquals(123456, feed.items[0].size)
        assertEquals("ep-7", feed.items[0].guid)
        assertTrue(feed.items[0].published > 0)
        assertTrue(feed.items[1].link.startsWith("magnet:"), "magnet links are preferred")
    }

    @Test
    fun parsesAtom() {
        val feed = RssParser.parse(atom.toByteArray())
        assertEquals("Atom feed", feed.title)
        assertEquals("https://example.org/album.torrent", feed.items.single().link)
        assertEquals(999, feed.items.single().size)
        assertTrue(feed.items.single().published > 0)
    }

    @Test
    fun rejectsExternalEntities() {
        val evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><title>&x;</title></channel></rss>"""
        val result = runCatching { RssParser.parse(evil.toByteArray()) }
        assertTrue(result.isFailure || !result.getOrThrow().title.contains("root"))
    }

    @Test
    fun matcherWordsAndRegex() {
        val words = RssRule(include = "show 1080p", exclude = "cam")
        assertTrue(RssMatcher.matches(words, "Show S02E07 1080p WEB"))
        assertFalse(RssMatcher.matches(words, "Show S02E07 720p"))
        assertFalse(RssMatcher.matches(words, "Show 1080p CAM"))
        val regex = RssRule(include = "S\\d{2}E0[1-9]", useRegex = true)
        assertTrue(RssMatcher.matches(regex, "Show S02E07"))
        assertFalse(RssMatcher.matches(regex, "Show S02E10"))
        assertFalse(RssMatcher.matches(regex.copy(enabled = false), "Show S02E07"))
        assertFalse(RssMatcher.matches(RssRule(include = "[broken", useRegex = true), "anything"))
    }
}
