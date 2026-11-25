package com.graphite.torrent.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IpFilterParserTest {
    @Test
    fun emuleDat() {
        assertEquals(IpRange("1.2.3.0", "1.2.3.255"), IpFilterParser.parseLine("001.002.003.000 - 001.002.003.255 , 000 , Some org"))
        assertNull(IpFilterParser.parseLine("001.002.003.000 - 001.002.003.255 , 200 , allowed"))
    }

    @Test
    fun peerGuardianP2p() {
        assertEquals(IpRange("10.0.0.0", "10.0.0.255"), IpFilterParser.parseLine("Bad: guys:10.0.0.0-10.0.0.255"))
    }

    @Test
    fun cidrAndSingle() {
        assertEquals(IpRange("192.168.1.0", "192.168.1.255"), IpFilterParser.parseLine("192.168.1.77/24"))
        assertEquals(IpRange("8.8.8.8", "8.8.8.8"), IpFilterParser.parseLine("8.8.8.8"))
        assertEquals(IpRange("0.0.0.0", "255.255.255.255"), IpFilterParser.parseLine("0.0.0.0/0"))
    }

    @Test
    fun ipv6Range() {
        assertEquals(IpRange("2001:db8::", "2001:db8::ffff"), IpFilterParser.parseLine("2001:db8:: - 2001:db8::ffff"))
    }

    @Test
    fun skipsGarbage() {
        val ranges = IpFilterParser.parse(sequenceOf("# comment", "", "not an ip", "300.1.1.1", "1.1.1.1"))
        assertEquals(listOf(IpRange("1.1.1.1", "1.1.1.1")), ranges)
    }
}
