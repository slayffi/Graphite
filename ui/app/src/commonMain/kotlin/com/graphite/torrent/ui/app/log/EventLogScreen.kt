package com.graphite.torrent.ui.app.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.model.LogEntry
import com.graphite.torrent.core.model.LogLevel
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.common.adviceRes
import com.graphite.torrent.ui.app.common.titleRes
import com.graphite.torrent.ui.app.details.formatDate
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.ButtonKind
import com.graphite.torrent.ui.designsystem.components.DetailRow
import com.graphite.torrent.ui.designsystem.components.GraphiteButton
import com.graphite.torrent.ui.designsystem.components.GraphiteIconButton
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Event log with readable sentences; errors also show what to do about them. */
@Composable
fun EventLogScreen(env: AppEnvironment, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val entries by env.eventLog.recent(500).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val spacing = GraphiteTheme.spacing
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            GraphiteIconButton(GraphiteIcons.ArrowLeft, stringResource(Res.string.back), onBack)
            Text(stringResource(Res.string.event_log), style = GraphiteTheme.typography.screenTitle, modifier = Modifier.weight(1f).padding(start = spacing.xs))
            GraphiteButton(stringResource(Res.string.clear), { scope.launch { env.eventLog.clear() } }, kind = ButtonKind.TERTIARY)
        }
        if (entries.isEmpty()) {
            Text(stringResource(Res.string.event_log_empty), style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary, modifier = Modifier.padding(spacing.lg))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(entries, key = { it.id }) { e -> LogRow(e) }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val colors = GraphiteTheme.colors
    val errorKind = if (entry.level == LogLevel.ERROR) entry.detail.substringBefore(':').let { k -> ErrorKind.entries.firstOrNull { it.name == k } } else null
    val subtitle = buildString {
        append(formatDate(entry.timestamp))
        if (errorKind != null) {
            append(" · ")
            append(stringResource(errorKind.titleRes()))
            append(". ")
            append(stringResource(errorKind.adviceRes()))
        } else if (entry.detail.isNotBlank()) {
            append(" · ")
            append(entry.detail)
        }
    }
    DetailRow(
        title = stringResource(entry.type.textRes(), entry.subject),
        subtitle = subtitle,
        value = "",
        icon = when (entry.level) {
            LogLevel.INFO -> GraphiteIcons.Info
            LogLevel.WARNING -> GraphiteIcons.CircleAlert
            LogLevel.ERROR -> GraphiteIcons.TriangleAlert
        },
        iconTint = if (entry.level == LogLevel.ERROR) colors.error else colors.textSecondary,
    )
}

private fun EventType.textRes(): StringResource = when (this) {
    EventType.ENGINE_STARTED -> Res.string.log_engine_started
    EventType.ENGINE_STOPPED -> Res.string.log_engine_stopped
    EventType.TORRENT_ADDED -> Res.string.log_added
    EventType.TORRENT_COMPLETED -> Res.string.log_completed
    EventType.TORRENT_REMOVED -> Res.string.log_removed
    EventType.TORRENT_ERROR -> Res.string.log_error
    EventType.TORRENT_MOVED -> Res.string.log_moved
    EventType.TORRENT_MOVE_FAILED -> Res.string.log_move_failed
    EventType.TORRENT_RELOCATED -> Res.string.log_relocated
    EventType.RESUME_REJECTED -> Res.string.log_resume_rejected
    EventType.PORT_CHANGED -> Res.string.log_port_changed
    EventType.PORT_MAPPED -> Res.string.log_port_mapped
    EventType.LISTEN_FAILED -> Res.string.log_listen_failed
    EventType.TRACKERS_UPDATED -> Res.string.log_trackers_updated
    EventType.TRACKERS_UPDATE_FAILED -> Res.string.log_trackers_failed
    EventType.IP_FILTER_LOADED -> Res.string.log_ip_filter_loaded
    EventType.IP_FILTER_FAILED -> Res.string.log_ip_filter_failed
    EventType.KILL_SWITCH_ENGAGED -> Res.string.log_kill_switch_on
    EventType.KILL_SWITCH_RELEASED -> Res.string.log_kill_switch_off
    EventType.SESSION_PAUSED -> Res.string.log_session_paused
    EventType.SESSION_RESUMED -> Res.string.log_session_resumed
    EventType.RSS_DOWNLOADED -> Res.string.log_rss_downloaded
    EventType.RSS_FAILED -> Res.string.log_rss_failed
    EventType.WATCH_FOLDER_ADDED -> Res.string.log_watch_added
    EventType.WATCH_FOLDER_FAILED -> Res.string.log_watch_failed
    EventType.SCHEDULE_APPLIED -> Res.string.log_schedule_applied
    EventType.REMOTE_CLIENT -> Res.string.log_remote_client
    EventType.SETTINGS_IMPORTED -> Res.string.log_settings_imported
    EventType.SETTINGS_EXPORTED -> Res.string.log_settings_exported
}
