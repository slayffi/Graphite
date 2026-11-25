package com.graphite.torrent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class LogLevel { INFO, WARNING, ERROR }

/** Every kind of entry in the event log; the UI maps each to a localized sentence. */
@Serializable
enum class EventType {
    ENGINE_STARTED,
    ENGINE_STOPPED,
    TORRENT_ADDED,
    TORRENT_COMPLETED,
    TORRENT_REMOVED,
    TORRENT_ERROR,
    TORRENT_MOVED,
    TORRENT_MOVE_FAILED,
    TORRENT_RELOCATED,
    RESUME_REJECTED,
    PORT_CHANGED,
    PORT_MAPPED,
    LISTEN_FAILED,
    TRACKERS_UPDATED,
    TRACKERS_UPDATE_FAILED,
    IP_FILTER_LOADED,
    IP_FILTER_FAILED,
    KILL_SWITCH_ENGAGED,
    KILL_SWITCH_RELEASED,
    SESSION_PAUSED,
    SESSION_RESUMED,
    RSS_DOWNLOADED,
    RSS_FAILED,
    WATCH_FOLDER_ADDED,
    WATCH_FOLDER_FAILED,
    SCHEDULE_APPLIED,
    REMOTE_CLIENT,
    SETTINGS_IMPORTED,
    SETTINGS_EXPORTED,
}

@Serializable
data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val type: EventType,
    val torrentId: String?,
    /** Usually a torrent name, port number, file name or URL. */
    val subject: String,
    /** Technical detail; for errors this is the [ErrorKind] name followed by the raw message. */
    val detail: String,
)

@Serializable
enum class HistoryEvent { ADDED, COMPLETED, REMOVED }

@Serializable
data class HistoryEntry(
    val id: Long,
    val torrentId: String,
    val name: String,
    val size: Long,
    val event: HistoryEvent,
    val timestamp: Long,
    val location: String,
)

@Serializable
data class DailyStat(
    /** Local epoch day. */
    val day: Long,
    val downloaded: Long,
    val uploaded: Long,
    val completed: Long,
    val added: Long,
    val peakRate: Long,
    val activeSeconds: Long,
)

@Serializable
data class StatsTotals(
    val downloaded: Long = 0,
    val uploaded: Long = 0,
    val completed: Long = 0,
    val added: Long = 0,
    val peakRate: Long = 0,
    val activeSeconds: Long = 0,
)

@Serializable
enum class StatsPeriod(val days: Int) { DAY(1), WEEK(7), MONTH(30) }

@Serializable
data class Category(val name: String, val savePath: String = "")
