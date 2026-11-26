package com.graphite.torrent.core.rss

import com.graphite.torrent.core.model.RssRule
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** One item of an RSS 2.0 or Atom feed that points to a torrent. */
data class ParsedRssItem(
    val guid: String,
    val title: String,
    val link: String,
    val published: Long,
    val size: Long,
)

data class ParsedFeed(val title: String, val items: List<ParsedRssItem>)

/** RSS 2.0 / Atom parser (DOM, external entities disabled). Prefers magnet links and torrent enclosures. */
object RssParser {

    fun parse(bytes: ByteArray): ParsedFeed {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val root = doc.documentElement
        return if (root.localName == "feed" || root.nodeName == "feed") parseAtom(root) else parseRss(root)
    }

    private fun parseRss(root: Element): ParsedFeed {
        val channel = root.children().firstOrNull { it.name() == "channel" } ?: root
        val title = channel.childText("title")
        val items = channel.children().filter { it.name() == "item" }.mapNotNull { item ->
            val itemTitle = item.childText("title")
            val enclosure = item.children().firstOrNull { it.name() == "enclosure" }
            val enclosureUrl = enclosure?.getAttribute("url").orEmpty()
            val magnet = item.children().firstOrNull { it.name() == "magnetURI" }?.textContent?.trim().orEmpty()
            val link = item.childText("link")
            val best = listOf(magnet, link.takeIf { it.startsWith("magnet:") }.orEmpty(), enclosureUrl, link).firstOrNull { it.isNotBlank() }
                ?: return@mapNotNull null
            val size = enclosure?.getAttribute("length")?.toLongOrNull()
                ?: item.children().firstOrNull { it.name() == "contentLength" }?.textContent?.trim()?.toLongOrNull() ?: 0L
            ParsedRssItem(
                guid = item.childText("guid").ifBlank { best },
                title = itemTitle.ifBlank { best },
                link = best,
                published = parseDate(item.childText("pubDate")),
                size = size,
            )
        }
        return ParsedFeed(title, items)
    }

    private fun parseAtom(root: Element): ParsedFeed {
        val title = root.childText("title")
        val items = root.children().filter { it.name() == "entry" }.mapNotNull { entry ->
            val links = entry.children().filter { it.name() == "link" }
            val href = links.map { it.getAttribute("href") }
            val best = href.firstOrNull { it.startsWith("magnet:") }
                ?: links.firstOrNull { it.getAttribute("type") == "application/x-bittorrent" }?.getAttribute("href")
                ?: href.firstOrNull { it.endsWith(".torrent") }
                ?: href.firstOrNull()
                ?: return@mapNotNull null
            ParsedRssItem(
                guid = entry.childText("id").ifBlank { best },
                title = entry.childText("title").ifBlank { best },
                link = best,
                published = parseDate(entry.childText("updated").ifBlank { entry.childText("published") }),
                size = links.firstOrNull { it.getAttribute("href") == best }?.getAttribute("length")?.toLongOrNull() ?: 0L,
            )
        }
        return ParsedFeed(title, items)
    }

    fun parseDate(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0
        runCatching { return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching {
            return ZonedDateTime.parse(t, DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH)).toInstant().toEpochMilli()
        }
        return 0
    }

    private fun Element.children(): List<Element> {
        val result = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) result += n as Element
        }
        return result
    }

    private fun Element.name(): String = localName ?: nodeName.substringAfter(':')

    private fun Element.childText(name: String): String =
        children().firstOrNull { it.name() == name }?.textContent?.trim().orEmpty()
}

/** Decides which feed items a rule downloads. */
object RssMatcher {
    fun matches(rule: RssRule, title: String): Boolean {
        if (!rule.enabled) return false
        val include = rule.include.trim()
        val exclude = rule.exclude.trim()
        return if (rule.useRegex) {
            val inc = include.isEmpty() || runCatching { Regex(include, RegexOption.IGNORE_CASE).containsMatchIn(title) }.getOrDefault(false)
            val exc = exclude.isNotEmpty() && runCatching { Regex(exclude, RegexOption.IGNORE_CASE).containsMatchIn(title) }.getOrDefault(false)
            inc && !exc
        } else {
            val t = title.lowercase()
            // Plain words: every word of "include" must appear; any word of "exclude" rejects.
            val inc = include.split(' ', ',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.all { it in t }
            val exc = exclude.split(' ', ',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { it in t }
            inc && !exc
        }
    }
}
