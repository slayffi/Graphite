package com.graphite.torrent.core.engine

import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.core.settings.EncryptionMode
import com.graphite.torrent.core.settings.ProxySettings
import com.graphite.torrent.core.settings.ProxyType
import com.graphite.torrent.core.settings.SpeedProfile
import org.libtorrent4j.swig.settings_pack
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies every setting name and enum constant against the real native library. */
class SettingNamesTest {
    @BeforeTest
    fun load() = NativeLibraries.ensureLoaded(null)

    @Test
    fun everyGeneratedSettingExists() {
        val configs = SpeedProfile.entries.flatMap { profile ->
            listOf(
                AppSettings(profile = profile),
                AppSettings(profile = profile, zeroUpload = false, encryption = EncryptionMode.REQUIRE,
                    proxy = ProxySettings(ProxyType.SOCKS5, "h", 1, "u", "p")),
            )
        }
        for (settings in configs) {
            val spec = SettingsFactory.build(EngineConfig(settings, 6881, 4))
            spec.ints.keys.forEach { SettingNames.int(it) }
            spec.bools.keys.forEach { SettingNames.bool(it) }
            spec.strings.keys.forEach { SettingNames.string(it) }
        }
    }

    @Test
    fun constantsMatchNativeEnums() {
        assertEquals(settings_pack.choking_algorithm_t.fixed_slots_choker.swigValue(), SettingsFactory.CHOKER_FIXED_SLOTS)
        assertEquals(settings_pack.enc_policy.pe_forced.swigValue(), SettingsFactory.ENC_FORCED)
        assertEquals(settings_pack.enc_policy.pe_enabled.swigValue(), SettingsFactory.ENC_ENABLED)
        assertEquals(settings_pack.enc_policy.pe_disabled.swigValue(), SettingsFactory.ENC_DISABLED)
        assertEquals(settings_pack.enc_level.pe_rc4.swigValue(), SettingsFactory.ENC_LEVEL_RC4)
        assertEquals(settings_pack.enc_level.pe_both.swigValue(), SettingsFactory.ENC_LEVEL_BOTH)
        assertEquals(settings_pack.proxy_type_t.none.swigValue(), SettingsFactory.PROXY_NONE)
        assertEquals(settings_pack.proxy_type_t.socks5.swigValue(), SettingsFactory.PROXY_SOCKS5)
        assertEquals(settings_pack.proxy_type_t.socks5_pw.swigValue(), SettingsFactory.PROXY_SOCKS5_PW)
        assertEquals(settings_pack.proxy_type_t.http.swigValue(), SettingsFactory.PROXY_HTTP)
        assertEquals(settings_pack.proxy_type_t.http_pw.swigValue(), SettingsFactory.PROXY_HTTP_PW)
        assertEquals(settings_pack.mmap_write_mode_t.always_pwrite.swigValue(), SettingsFactory.WRITE_ALWAYS_PWRITE)
        assertEquals(settings_pack.mmap_write_mode_t.always_mmap_write.swigValue(), SettingsFactory.WRITE_ALWAYS_MMAP)
        assertEquals(settings_pack.mmap_write_mode_t.auto_mmap_write.swigValue(), SettingsFactory.WRITE_AUTO)
        assertEquals(settings_pack.bandwidth_mixed_algo_t.prefer_tcp.swigValue(), SettingsFactory.MIXED_PREFER_TCP)
        assertEquals(settings_pack.bandwidth_mixed_algo_t.peer_proportional.swigValue(), SettingsFactory.MIXED_PEER_PROPORTIONAL)
        assertEquals(settings_pack.suggest_mode_t.no_piece_suggestions.swigValue(), SettingsFactory.SUGGEST_NONE)
    }

    @Test
    fun parsesTorrentMetadata() {
        val dir = kotlin.io.path.createTempDirectory("meta").toFile()
        val file = TestTorrents.createPayload(dir, "movie.mkv", 300_000)
        val bytes = TestTorrents.torrentFor(file, pieceLength = 64 * 1024)
        val info = TorrentMapping.parseTorrentInfo(bytes)
        val meta = TorrentMapping.metadataOf(info, bytes)
        assertEquals("movie.mkv", meta.name)
        assertEquals(300_000L, meta.totalSize)
        assertEquals(5, meta.pieceCount)
        assertEquals("test torrent", meta.comment)
        assertEquals("Graphite tests", meta.createdBy)
        assertEquals(1, meta.files.size)
        dir.deleteRecursively()
    }

    @Test
    fun rejectsBrokenTorrent() {
        kotlin.test.assertFailsWith<com.graphite.torrent.core.model.GraphiteException> {
            TorrentMapping.parseTorrentInfo("d4:infoi1ee".toByteArray())
        }
    }
}
