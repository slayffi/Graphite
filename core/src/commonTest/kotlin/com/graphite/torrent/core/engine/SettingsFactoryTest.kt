package com.graphite.torrent.core.engine

import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.core.settings.EncryptionMode
import com.graphite.torrent.core.settings.ProxySettings
import com.graphite.torrent.core.settings.ProxyType
import com.graphite.torrent.core.settings.SpeedProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsFactoryTest {
    private fun spec(settings: AppSettings = AppSettings(), cores: Int = 8, port: Int = 51413, block: (EngineConfig) -> EngineConfig = { it }) =
        SettingsFactory.build(block(EngineConfig(settings, port, cores)))

    @Test
    fun turboIsDefaultAndAggressive() {
        val s = spec()
        assertEquals(SpeedProfile.TURBO, AppSettings().profile)
        assertTrue(s.ints.getValue("connections_limit") >= 500)
        assertTrue(s.ints.getValue("max_out_request_queue") > 500)
        assertTrue(s.ints.getValue("connection_speed") > 30)
        assertTrue(s.ints.getValue("torrent_connect_boost") > 30)
        assertTrue(s.ints.getValue("send_socket_buffer_size") >= 1024 * 1024)
        assertTrue(s.ints.getValue("recv_socket_buffer_size") >= 1024 * 1024)
        assertEquals(16, s.ints.getValue("aio_threads"), "aio threads scale with 8 cores")
        assertTrue(s.bools.getValue("enable_dht"))
        assertTrue(s.bools.getValue("enable_lsd"))
        assertTrue(s.bools.getValue("enable_upnp"))
        assertTrue(s.bools.getValue("enable_natpmp"))
        assertTrue(s.bools.getValue("enable_outgoing_utp"))
        assertTrue(s.bools.getValue("enable_outgoing_tcp"))
    }

    @Test
    fun noDownloadLimitByDefault() {
        assertEquals(0, spec().ints.getValue("download_rate_limit"))
    }

    @Test
    fun zeroUploadClosesEveryUploadPath() {
        val s = spec()
        assertEquals(0, s.ints.getValue("unchoke_slots_limit"))
        assertEquals(SettingsFactory.CHOKER_FIXED_SLOTS, s.ints.getValue("choking_algorithm"))
        assertEquals(0, s.ints.getValue("allowed_fast_set_size"))
        assertEquals(0, s.ints.getValue("active_seeds"))
        assertFalse(s.bools.getValue("seeding_outgoing_connections"))
        // A tiny upload limit would also throttle block requests and kill download speed.
        assertEquals(0, s.ints.getValue("upload_rate_limit"))
    }

    @Test
    fun uploadAllowedWhenZeroUploadDisabled() {
        val s = spec(AppSettings(zeroUpload = false, uploadLimitKbps = 100))
        assertTrue(s.ints.getValue("unchoke_slots_limit") > 0)
        assertTrue(s.ints.getValue("allowed_fast_set_size") > 0)
        assertEquals(100 * 1024, s.ints.getValue("upload_rate_limit"))
    }

    @Test
    fun economyUsesFewerResources() {
        val turbo = spec()
        val eco = spec(AppSettings(profile = SpeedProfile.ECONOMY))
        assertTrue(eco.ints.getValue("connections_limit") < turbo.ints.getValue("connections_limit"))
        assertTrue(eco.ints.getValue("aio_threads") < turbo.ints.getValue("aio_threads"))
        assertTrue(eco.ints.getValue("max_queued_disk_bytes") < turbo.ints.getValue("max_queued_disk_bytes"))
    }

    @Test
    fun profileOverrideWins() {
        val s = spec { it.copy(profileOverride = SpeedProfile.ECONOMY) }
        assertEquals(ProfileTuning.of(SpeedProfile.ECONOMY, 8).connectionsLimit, s.ints.getValue("connections_limit"))
    }

    @Test
    fun userOverridesTakePriority() {
        val s = spec(AppSettings(connectionsLimit = 1234, maxActiveDownloads = 2, diskQueueMb = 100, aioThreads = 3, downloadLimitKbps = 500))
        assertEquals(1234, s.ints.getValue("connections_limit"))
        assertEquals(2, s.ints.getValue("active_downloads"))
        assertEquals(100 * 1024 * 1024, s.ints.getValue("max_queued_disk_bytes"))
        assertEquals(3, s.ints.getValue("aio_threads"))
        assertEquals(500 * 1024, s.ints.getValue("download_rate_limit"))
    }

    @Test
    fun schedulerLimitOverridesSettings() {
        val s = spec(AppSettings(downloadLimitKbps = 500)) { it.copy(downloadLimitOverrideKbps = 50) }
        assertEquals(50 * 1024, s.ints.getValue("download_rate_limit"))
    }

    @Test
    fun listenInterfacesIncludeIpv6() {
        assertEquals("0.0.0.0:6881,[::]:6881", spec(port = 6881).strings.getValue("listen_interfaces"))
        assertEquals("0.0.0.0:6881", spec(AppSettings(enableIpv6 = false), port = 6881).strings.getValue("listen_interfaces"))
    }

    @Test
    fun bindingToVpnInterface() {
        val s = spec(port = 7000) { it.copy(bindAddresses = listOf("10.8.0.2", "fd00::2")) }
        assertEquals("10.8.0.2:7000,[fd00::2]:7000", s.strings.getValue("listen_interfaces"))
        assertEquals("10.8.0.2,fd00::2", s.strings.getValue("outgoing_interfaces"))
    }

    @Test
    fun killSwitchBlocksAllDiscovery() {
        val s = spec(port = 7000) { it.copy(killSwitchBlocked = true) }
        assertEquals("127.0.0.1:7000", s.strings.getValue("listen_interfaces"))
        assertEquals("127.0.0.1", s.strings.getValue("outgoing_interfaces"))
        assertFalse(s.bools.getValue("enable_dht"))
        assertFalse(s.bools.getValue("enable_lsd"))
        assertFalse(s.bools.getValue("enable_upnp"))
        assertFalse(s.bools.getValue("enable_natpmp"))
    }

    @Test
    fun encryptionPolicies() {
        val prefer = spec()
        assertEquals(SettingsFactory.ENC_ENABLED, prefer.ints.getValue("out_enc_policy"))
        val require = spec(AppSettings(encryption = EncryptionMode.REQUIRE))
        assertEquals(SettingsFactory.ENC_FORCED, require.ints.getValue("in_enc_policy"))
        assertEquals(SettingsFactory.ENC_LEVEL_RC4, require.ints.getValue("allowed_enc_level"))
        val off = spec(AppSettings(encryption = EncryptionMode.DISABLE))
        assertEquals(SettingsFactory.ENC_DISABLED, off.ints.getValue("out_enc_policy"))
    }

    @Test
    fun proxyTypes() {
        val socks = spec(AppSettings(proxy = ProxySettings(ProxyType.SOCKS5, "127.0.0.1", 9050)))
        assertEquals(SettingsFactory.PROXY_SOCKS5, socks.ints.getValue("proxy_type"))
        assertEquals("127.0.0.1", socks.strings.getValue("proxy_hostname"))
        val socksAuth = spec(AppSettings(proxy = ProxySettings(ProxyType.SOCKS5, "h", 1, "user", "pass")))
        assertEquals(SettingsFactory.PROXY_SOCKS5_PW, socksAuth.ints.getValue("proxy_type"))
        val http = spec(AppSettings(proxy = ProxySettings(ProxyType.HTTP, "h", 8080)))
        assertEquals(SettingsFactory.PROXY_HTTP, http.ints.getValue("proxy_type"))
        assertEquals(SettingsFactory.PROXY_NONE, spec().ints.getValue("proxy_type"))
    }

    @Test
    fun neverDisablesBothTransports() {
        val s = spec(AppSettings(enableTcp = false, enableUtp = false))
        assertTrue(s.bools.getValue("enable_outgoing_tcp"))
    }
}
