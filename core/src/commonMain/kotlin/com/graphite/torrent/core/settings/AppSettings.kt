package com.graphite.torrent.core.settings

import kotlinx.serialization.Serializable

/** Speed profiles. TURBO is the default. */
@Serializable
enum class SpeedProfile { TURBO, BALANCED, ECONOMY }

/** Protocol encryption policy (maps to libtorrent pe_enabled / pe_forced / pe_disabled). */
@Serializable
enum class EncryptionMode { PREFER, REQUIRE, DISABLE }

@Serializable
enum class ProxyType { NONE, SOCKS5, HTTP }

@Serializable
data class ProxySettings(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val proxyPeerConnections: Boolean = true,
    val proxyTrackerConnections: Boolean = true,
    val proxyHostnames: Boolean = true,
)

/** libtorrent 2.x disk back-end. MMAP relies on the OS page cache, POSIX uses pread/pwrite. */
@Serializable
enum class DiskIoMode { MMAP, POSIX }

/** How the mmap back-end writes (maps to settings_pack::disk_write_mode). */
@Serializable
enum class DiskWriteMode { AUTO, MMAP, PWRITE }

@Serializable
enum class CompletionSystemAction { NONE, SLEEP, SHUTDOWN, QUIT_APP }

@Serializable
enum class WatchFolderAction { RENAME, DELETE }

/** Pairing data stored on the Android side for controlling a desktop client. */
@Serializable
data class RemotePairing(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val keyBase64: String,
)

/**
 * All user settings. Persisted as JSON through a typed DataStore, so adding a field with a default
 * value is always backward compatible.
 */
@Serializable
data class AppSettings(
    // Downloads
    val defaultSavePath: String = "",
    val androidTreeUri: String = "",
    val moveCompletedTo: String = "",
    val preallocate: Boolean = true,
    val sequentialByDefault: Boolean = false,
    val startPaused: Boolean = false,
    val maxActiveDownloads: Int = 0,
    val downloadLimitKbps: Int = 0,

    // Speed
    val profile: SpeedProfile = SpeedProfile.TURBO,

    // Upload
    val zeroUpload: Boolean = true,
    val uploadLimitKbps: Int = 0,

    // Network
    val listenPort: Int = 0,
    val autoPort: Boolean = true,
    val enableDht: Boolean = true,
    val enableLsd: Boolean = true,
    val enableUpnp: Boolean = true,
    val enableNatPmp: Boolean = true,
    val enableUtp: Boolean = true,
    val enableTcp: Boolean = true,
    val enableIpv6: Boolean = true,
    val enablePex: Boolean = true,
    val connectionsLimit: Int = 0,

    // Disk
    val diskQueueMb: Int = 0,
    val diskIoMode: DiskIoMode = DiskIoMode.MMAP,
    val diskWriteMode: DiskWriteMode = DiskWriteMode.AUTO,
    val aioThreads: Int = 0,

    // Trackers
    val addPublicTrackers: Boolean = true,
    val trackersAutoUpdate: Boolean = true,
    val trackerSources: List<String> = listOf(DEFAULT_TRACKER_SOURCE),
    val trackerList: List<String> = DEFAULT_TRACKERS,
    val trackersUpdatedAt: Long = 0,

    // Privacy
    val encryption: EncryptionMode = EncryptionMode.PREFER,
    val proxy: ProxySettings = ProxySettings(),
    val bindInterface: String = "",
    val killSwitch: Boolean = false,
    val ipFilterPath: String = "",
    val ipFilterEnabled: Boolean = false,
    val anonymousMode: Boolean = false,

    // Completion
    val notifyOnComplete: Boolean = true,
    val soundOnComplete: Boolean = false,
    val vibrateOnComplete: Boolean = true,
    val completionAction: CompletionSystemAction = CompletionSystemAction.NONE,

    // Android power / network rules
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val pauseOnLowBattery: Boolean = true,
    val lowBatteryThreshold: Int = 15,

    // Desktop
    val minimizeToTray: Boolean = true,
    val startMinimized: Boolean = false,
    val autostart: Boolean = false,
    val globalHotkeys: Boolean = true,
    val associateTorrentFiles: Boolean = true,
    val associateMagnetLinks: Boolean = true,

    // Appearance
    val amoledBlack: Boolean = false,
    val reduceMotion: Boolean = false,

    // Watch folder
    val watchFolderEnabled: Boolean = false,
    val watchFolderPath: String = "",
    val watchFolderAction: WatchFolderAction = WatchFolderAction.RENAME,

    // Remote control
    val remoteServerEnabled: Boolean = false,
    val remotePort: Int = DEFAULT_REMOTE_PORT,
    val remoteKeyBase64: String = "",
    val remotePairing: RemotePairing? = null,
    val remoteMode: Boolean = false,

    // Scheduler / RSS master switches (rules are stored in the database)
    val schedulerEnabled: Boolean = false,
    val rssEnabled: Boolean = true,
    val rssRefreshMinutes: Int = 30,

    // Onboarding
    val firstRunDone: Boolean = false,
) {
    companion object {
        const val DEFAULT_REMOTE_PORT = 48650
        const val DEFAULT_TRACKER_SOURCE =
            "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"

        /** Built-in fallback list used until the first successful update. */
        val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.dler.org:6969/announce",
        )
    }
}
