package com.graphite.torrent.ui.designsystem

import com.graphite.torrent.ui.designsystem.components.middleEllipsis
import com.graphite.torrent.ui.designsystem.format.Formatters
import com.graphite.torrent.ui.designsystem.theme.UnitLabels
import kotlin.test.Test
import kotlin.test.assertEquals

class FormattersTest {
    private val en = UnitLabels.English
    private val ru = UnitLabels.Russian

    @Test
    fun bytes() {
        assertEquals("512 B", Formatters.bytes(512, en))
        assertEquals("1.0 KB", Formatters.bytes(1024, en))
        assertEquals("9,8 МБ", Formatters.bytes((9.8 * 1024 * 1024).toLong(), ru))
        assertEquals("123 MB", Formatters.bytes(123L * 1024 * 1024, en))
        assertEquals("1.5 GB", Formatters.bytes(1536L * 1024 * 1024, en))
        assertEquals("0 B", Formatters.bytes(-5, en))
    }

    @Test
    fun speedPercentDuration() {
        assertEquals("2.0 MB/s", Formatters.speed(2L * 1024 * 1024, en))
        assertEquals("0 Б/с", Formatters.speed(0, ru))
        assertEquals("5,5%", Formatters.percent(0.055f, ru))
        assertEquals("42%", Formatters.percent(0.42f, en))
        assertEquals("100%", Formatters.percent(1f, en))
        assertEquals("∞", Formatters.duration(-1, en))
        assertEquals("45s", Formatters.duration(45, en))
        assertEquals("12m 05s", Formatters.duration(725, en))
        assertEquals("3ч 20м", Formatters.duration(3 * 3600 + 20 * 60, ru))
        assertEquals("2d 4h", Formatters.duration(2 * 86_400 + 4 * 3600, en))
        assertEquals("1:05:09", Formatters.clock(3_909_000))
        assertEquals("4:09", Formatters.clock(249_000))
    }

    @Test
    fun middleEllipsisKeepsTheEnd() {
        val name = "Some.Long.Series.Name.S02E07.1080p.WEB-DL.mkv"
        assertEquals(name, middleEllipsis(name, name.length))
        val cut = middleEllipsis(name, 20)
        assertEquals(20, cut.length)
        assertEquals("Some.Lon….WEB-DL.mkv", cut)
        assertEquals("…", middleEllipsis(name, 1))
    }
}
