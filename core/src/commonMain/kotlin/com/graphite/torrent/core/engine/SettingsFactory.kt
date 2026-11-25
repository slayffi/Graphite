package com.graphite.torrent.core.engine

import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.core.settings.DiskWriteMode
import com.graphite.torrent.core.settings.EncryptionMode
import com.graphite.torrent.core.settings.ProxyType
import com.graphite.torrent.core.settings.SpeedProfile

/** Everything the engine needs to build a libtorrent settings pack. */
data class EngineConfig(
    val settings: AppSettings,
    val listenPort: Int,
    val cpuCores: Int,
    /** Profile forced by the scheduler or by battery rules; null = use the settings profile. */
    val profileOverride: SpeedProfile? = null,
    /** Download limit forced by the scheduler; null = use the settings limit. */
    val downloadLimitOverrideKbps: Int? = null,
    /** Upload limit forced by the scheduler (only used when zero-upload is off). */
    val uploadLimitOverrideKbps: Int? = null,
    /** IP addresses of the interface the user bound to (VPN). Empty = all interfaces. */
    val bindAddresses: List<String> = emptyList(),
    /** True when a bind interface is configured but currently missing: the kill switch blocks all traffic. */
    val killSwitchBlocked: Boolean = false,
)

/** Pure description of a libtorrent settings pack: setting name → value. */
data class SettingsSpec(
    val ints: Map<String, Int>,
    val bools: Map<String, Boolean>,
    val strings: Map<String, String>,
)

/** Numbers that differ between the speed profiles. */
data class ProfileTuning(
    val connectionsLimit: Int,
    val maxOutRequestQueue: Int,
    val connectionSpeed: Int,
    val torrentConnectBoost: Int,
    val socketBufferBytes: Int,
    val maxPeerRecvBufferBytes: Int,
    val maxPeerlistSize: Int,
    val listenQueueSize: Int,
    val activeDownloads: Int,
    val activeChecking: Int,
    val diskQueueMb: Int,
    val aioThreads: Int,
    val hashingThreads: Int,
    val checkingMemBlocks: Int,
    val filePoolSize: Int,
    val tickIntervalMs: Int,
    val mixedModeAlgorithm: Int,
    val peerConnectTimeoutSec: Int,
) {
    companion object {
        private const val KIB = 1024
        private const val MIB = 1024 * 1024

        fun of(profile: SpeedProfile, cpuCores: Int): ProfileTuning {
            val cores = cpuCores.coerceAtLeast(1)
            return when (profile) {
                SpeedProfile.TURBO -> ProfileTuning(
                    connectionsLimit = 800,
                    maxOutRequestQueue = 1500,
                    connectionSpeed = 200,
                    torrentConnectBoost = 100,
                    socketBufferBytes = 1 * MIB,
                    maxPeerRecvBufferBytes = 4 * MIB,
                    maxPeerlistSize = 8000,
                    listenQueueSize = 200,
                    activeDownloads = 8,
                    activeChecking = 2,
                    diskQueueMb = 64,
                    aioThreads = (cores * 2).coerceIn(4, 32),
                    hashingThreads = cores.coerceIn(2, 16),
                    checkingMemBlocks = 2048,
                    filePoolSize = 300,
                    tickIntervalMs = 500,
                    mixedModeAlgorithm = SettingsFactory.MIXED_PEER_PROPORTIONAL,
                    peerConnectTimeoutSec = 10,
                )

                SpeedProfile.BALANCED -> ProfileTuning(
                    connectionsLimit = 400,
                    maxOutRequestQueue = 600,
                    connectionSpeed = 60,
                    torrentConnectBoost = 40,
                    socketBufferBytes = 512 * KIB,
                    maxPeerRecvBufferBytes = 2 * MIB,
                    maxPeerlistSize = 4000,
                    listenQueueSize = 64,
                    activeDownloads = 5,
                    activeChecking = 1,
                    diskQueueMb = 32,
                    aioThreads = cores.coerceIn(2, 16),
                    hashingThreads = (cores / 2).coerceIn(1, 8),
                    checkingMemBlocks = 1024,
                    filePoolSize = 150,
                    tickIntervalMs = 500,
                    mixedModeAlgorithm = SettingsFactory.MIXED_PEER_PROPORTIONAL,
                    peerConnectTimeoutSec = 15,
                )

                SpeedProfile.ECONOMY -> ProfileTuning(
                    connectionsLimit = 120,
                    maxOutRequestQueue = 250,
                    connectionSpeed = 15,
                    torrentConnectBoost = 10,
                    socketBufferBytes = 0,
                    maxPeerRecvBufferBytes = 1 * MIB,
                    maxPeerlistSize = 1000,
                    listenQueueSize = 16,
                    activeDownloads = 3,
                    activeChecking = 1,
                    diskQueueMb = 8,
                    aioThreads = 2,
                    hashingThreads = 1,
                    checkingMemBlocks = 256,
                    filePoolSize = 60,
                    tickIntervalMs = 1000,
                    mixedModeAlgorithm = SettingsFactory.MIXED_PREFER_TCP,
                    peerConnectTimeoutSec = 20,
                )
            }
        }
    }
}

/**
 * Builds the libtorrent settings from user settings.
 *
 * Zero-upload is enforced without an upload rate limit on purpose: libtorrent's limiter throttles every
 * byte a peer connection sends, including block requests, so a tiny limit would cripple downloads.
 * Instead no peer is ever unchoked (`unchoke_slots_limit = 0`, which also disables optimistic unchokes),
 * no allowed-fast set is offered (`allowed_fast_set_size = 0`) and the "local" peer class, whose peers
 * ignore unchoke slots, is removed by [LibtorrentEngine] through the peer class filter.
 */
object SettingsFactory {
    // settings_pack::choking_algorithm_t
    const val CHOKER_FIXED_SLOTS = 0

    // settings_pack::bandwidth_mixed_algo_t
    const val MIXED_PREFER_TCP = 0
    const val MIXED_PEER_PROPORTIONAL = 1

    // settings_pack::enc_policy
    const val ENC_FORCED = 0
    const val ENC_ENABLED = 1
    const val ENC_DISABLED = 2

    // settings_pack::enc_level
    const val ENC_LEVEL_RC4 = 2
    const val ENC_LEVEL_BOTH = 3

    // settings_pack::proxy_type_t
    const val PROXY_NONE = 0
    const val PROXY_SOCKS5 = 2
    const val PROXY_SOCKS5_PW = 3
    const val PROXY_HTTP = 4
    const val PROXY_HTTP_PW = 5

    // settings_pack::mmap_write_mode_t
    const val WRITE_ALWAYS_PWRITE = 0
    const val WRITE_ALWAYS_MMAP = 1
    const val WRITE_AUTO = 2

    // settings_pack::suggest_mode_t
    const val SUGGEST_NONE = 0

    const val CLIENT_NAME = "Graphite"
    const val CLIENT_VERSION = "1.0.0"
    const val PEER_FINGERPRINT = "-GR1000-"

    private const val MIB = 1024 * 1024
    private const val DEFAULT_UNCHOKE_SLOTS = 8
    private const val DEFAULT_ALLOWED_FAST = 5

    fun build(config: EngineConfig): SettingsSpec {
        val s = config.settings
        val profile = config.profileOverride ?: s.profile
        val t = ProfileTuning.of(profile, config.cpuCores)
        val ints = LinkedHashMap<String, Int>()
        val bools = LinkedHashMap<String, Boolean>()
        val strings = LinkedHashMap<String, String>()

        // Connections and request pipeline
        ints["connections_limit"] = if (s.connectionsLimit > 0) s.connectionsLimit else t.connectionsLimit
        ints["max_out_request_queue"] = t.maxOutRequestQueue
        ints["connection_speed"] = t.connectionSpeed
        ints["torrent_connect_boost"] = t.torrentConnectBoost
        ints["send_socket_buffer_size"] = t.socketBufferBytes
        ints["recv_socket_buffer_size"] = t.socketBufferBytes
        ints["max_peer_recv_buffer_size"] = t.maxPeerRecvBufferBytes
        ints["max_peerlist_size"] = t.maxPeerlistSize
        ints["listen_queue_size"] = t.listenQueueSize
        ints["peer_connect_timeout"] = t.peerConnectTimeoutSec
        ints["mixed_mode_algorithm"] = t.mixedModeAlgorithm
        ints["tick_interval"] = t.tickIntervalMs

        // Queue
        val activeDownloads = if (s.maxActiveDownloads > 0) s.maxActiveDownloads else t.activeDownloads
        val activeSeeds = if (s.zeroUpload) 0 else 5
        ints["active_downloads"] = activeDownloads
        ints["active_seeds"] = activeSeeds
        ints["active_limit"] = activeDownloads + activeSeeds + 10
        ints["active_checking"] = t.activeChecking
        bools["dont_count_slow_torrents"] = true

        // Disk
        ints["aio_threads"] = if (s.aioThreads > 0) s.aioThreads else t.aioThreads
        ints["hashing_threads"] = t.hashingThreads
        ints["max_queued_disk_bytes"] = (if (s.diskQueueMb > 0) s.diskQueueMb else t.diskQueueMb) * MIB
        ints["disk_write_mode"] = when (s.diskWriteMode) {
            DiskWriteMode.AUTO -> WRITE_AUTO
            DiskWriteMode.MMAP -> WRITE_ALWAYS_MMAP
            DiskWriteMode.PWRITE -> WRITE_ALWAYS_PWRITE
        }
        ints["checking_mem_usage"] = t.checkingMemBlocks
        ints["file_pool_size"] = t.filePoolSize
        bools["enable_set_file_valid_data"] = s.preallocate
        bools["no_atime_storage"] = true
        bools["piece_extent_affinity"] = true

        // Rate limits (0 = unlimited in libtorrent). No download limit by default.
        val downloadKbps = config.downloadLimitOverrideKbps ?: s.downloadLimitKbps
        ints["download_rate_limit"] = downloadKbps.coerceAtLeast(0) * 1024
        bools["rate_limit_ip_overhead"] = false

        // Upload policy
        if (s.zeroUpload) {
            ints["unchoke_slots_limit"] = 0
            ints["choking_algorithm"] = CHOKER_FIXED_SLOTS
            ints["allowed_fast_set_size"] = 0
            ints["upload_rate_limit"] = 0
            ints["suggest_mode"] = SUGGEST_NONE
            bools["seeding_outgoing_connections"] = false
            bools["close_redundant_connections"] = true
        } else {
            val uploadKbps = config.uploadLimitOverrideKbps ?: s.uploadLimitKbps
            ints["unchoke_slots_limit"] = DEFAULT_UNCHOKE_SLOTS
            ints["choking_algorithm"] = CHOKER_FIXED_SLOTS
            ints["allowed_fast_set_size"] = DEFAULT_ALLOWED_FAST
            ints["upload_rate_limit"] = uploadKbps.coerceAtLeast(0) * 1024
            ints["suggest_mode"] = SUGGEST_NONE
            bools["seeding_outgoing_connections"] = true
            bools["close_redundant_connections"] = true
        }

        // Peer discovery and transports
        val blocked = config.killSwitchBlocked
        bools["enable_dht"] = s.enableDht && !blocked
        bools["enable_lsd"] = s.enableLsd && !blocked
        bools["enable_upnp"] = s.enableUpnp && !blocked
        bools["enable_natpmp"] = s.enableNatPmp && !blocked
        val tcp = s.enableTcp || !s.enableUtp
        bools["enable_outgoing_utp"] = s.enableUtp
        bools["enable_incoming_utp"] = s.enableUtp
        bools["enable_outgoing_tcp"] = tcp
        bools["enable_incoming_tcp"] = tcp
        bools["announce_to_all_tiers"] = true
        bools["announce_to_all_trackers"] = true
        bools["prefer_udp_trackers"] = true
        bools["enable_ip_notifier"] = true
        bools["validate_https_trackers"] = true
        bools["ssrf_mitigation"] = true
        bools["apply_ip_filter_to_trackers"] = true
        bools["anonymous_mode"] = s.anonymousMode

        // Encryption
        val policy = when (s.encryption) {
            EncryptionMode.PREFER -> ENC_ENABLED
            EncryptionMode.REQUIRE -> ENC_FORCED
            EncryptionMode.DISABLE -> ENC_DISABLED
        }
        ints["out_enc_policy"] = policy
        ints["in_enc_policy"] = policy
        ints["allowed_enc_level"] = if (s.encryption == EncryptionMode.REQUIRE) ENC_LEVEL_RC4 else ENC_LEVEL_BOTH
        bools["prefer_rc4"] = s.encryption == EncryptionMode.REQUIRE

        // Proxy
        val p = s.proxy
        val hasCredentials = p.username.isNotEmpty()
        ints["proxy_type"] = when (p.type) {
            ProxyType.NONE -> PROXY_NONE
            ProxyType.SOCKS5 -> if (hasCredentials) PROXY_SOCKS5_PW else PROXY_SOCKS5
            ProxyType.HTTP -> if (hasCredentials) PROXY_HTTP_PW else PROXY_HTTP
        }
        strings["proxy_hostname"] = if (p.type == ProxyType.NONE) "" else p.host.trim()
        ints["proxy_port"] = p.port.coerceIn(0, 65535)
        strings["proxy_username"] = p.username
        strings["proxy_password"] = p.password
        bools["proxy_peer_connections"] = p.proxyPeerConnections
        bools["proxy_tracker_connections"] = p.proxyTrackerConnections
        bools["proxy_hostnames"] = p.proxyHostnames

        // Interfaces
        strings["listen_interfaces"] = listenInterfaces(config)
        strings["outgoing_interfaces"] = when {
            blocked -> "127.0.0.1"
            config.bindAddresses.isNotEmpty() -> config.bindAddresses.joinToString(",")
            else -> ""
        }

        // Identity
        strings["user_agent"] = "$CLIENT_NAME/$CLIENT_VERSION"
        strings["handshake_client_version"] = "$CLIENT_NAME/$CLIENT_VERSION"
        strings["peer_fingerprint"] = PEER_FINGERPRINT

        ints["alert_queue_size"] = 10_000
        return SettingsSpec(ints, bools, strings)
    }

    fun listenInterfaces(config: EngineConfig): String {
        val port = config.listenPort.coerceIn(0, 65535)
        if (config.killSwitchBlocked) return "127.0.0.1:$port"
        if (config.bindAddresses.isNotEmpty()) {
            return config.bindAddresses.joinToString(",") { formatEndpoint(it, port) }
        }
        return if (config.settings.enableIpv6) "0.0.0.0:$port,[::]:$port" else "0.0.0.0:$port"
    }

    fun formatEndpoint(address: String, port: Int): String =
        if (address.contains(':') && !address.startsWith("[")) "[$address]:$port" else "$address:$port"
}
