package com.graphite.torrent.core.model

import com.graphite.torrent.core.settings.SpeedProfile
import kotlinx.serialization.Serializable

@Serializable
enum class ScheduleAction {
    /** Pause every download while the rule is active. */
    PAUSE,

    /** Apply speed limits (KB/s, 0 = unlimited). */
    LIMIT,

    /** Switch the speed profile. */
    PROFILE,
}

/**
 * A time window on selected weekdays. [days] is a bit mask: Monday = 1, Tuesday = 2 … Sunday = 64.
 * Minutes are minutes after midnight; when [endMinute] <= [startMinute] the window crosses midnight.
 */
@Serializable
data class ScheduleRule(
    val id: Long = 0,
    val days: Int = ALL_DAYS,
    val startMinute: Int = 0,
    val endMinute: Int = 0,
    val action: ScheduleAction = ScheduleAction.PAUSE,
    val downloadKbps: Int = 0,
    val uploadKbps: Int = 0,
    val profile: SpeedProfile? = null,
    val enabled: Boolean = true,
) {
    companion object {
        const val ALL_DAYS = 0b1111111
        const val WORKDAYS = 0b0011111
        const val WEEKEND = 0b1100000
    }
}

/** What the scheduler wants right now. */
@Serializable
data class ScheduleDecision(
    val pause: Boolean = false,
    val downloadLimitKbps: Int? = null,
    val uploadLimitKbps: Int? = null,
    val profile: SpeedProfile? = null,
    val activeRuleIds: List<Long> = emptyList(),
)

@Serializable
data class RssFeed(
    val id: Long,
    val url: String,
    val title: String,
    val enabled: Boolean,
    val lastChecked: Long,
    val lastError: String,
)

@Serializable
data class RssRule(
    val id: Long = 0,
    val name: String = "",
    /** null = all feeds. */
    val feedId: Long? = null,
    val include: String = "",
    val exclude: String = "",
    val useRegex: Boolean = false,
    val category: String? = null,
    val savePath: String = "",
    val startPaused: Boolean = false,
    val enabled: Boolean = true,
)

@Serializable
data class RssEntry(
    val feedId: Long,
    val guid: String,
    val title: String,
    /** Magnet link or URL of a .torrent file. */
    val link: String,
    val published: Long,
    val size: Long,
    val downloaded: Boolean,
)
