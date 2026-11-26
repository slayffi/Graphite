package com.graphite.torrent.ui.app.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graphite.torrent.core.model.HistoryEvent
import com.graphite.torrent.core.model.StatsPeriod
import com.graphite.torrent.ui.app.common.units
import com.graphite.torrent.ui.app.details.formatDate
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.BarChart
import com.graphite.torrent.ui.designsystem.components.BarValue
import com.graphite.torrent.ui.designsystem.components.DetailRow
import com.graphite.torrent.ui.designsystem.components.GraphiteCard
import com.graphite.torrent.ui.designsystem.components.SectionHeader
import com.graphite.torrent.ui.designsystem.components.SegmentedControl
import com.graphite.torrent.ui.designsystem.components.SpeedChart
import com.graphite.torrent.ui.designsystem.format.Formatters
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate

@Composable
fun StatsScreen(viewModel: StatsViewModel, modifier: Modifier = Modifier) {
    val period by viewModel.period.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val speed by viewModel.speedHistory.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val spacing = GraphiteTheme.spacing
    val u = units()

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Text(stringResource(Res.string.tab_stats), style = GraphiteTheme.typography.screenTitle, modifier = Modifier.semantics { heading() })
        }
        item {
            SegmentedControl(
                options = StatsPeriod.entries,
                selected = period,
                label = { p ->
                    stringResource(
                        when (p) {
                            StatsPeriod.DAY -> Res.string.period_day
                            StatsPeriod.WEEK -> Res.string.period_week
                            StatsPeriod.MONTH -> Res.string.period_month
                        },
                    )
                },
                onSelect = viewModel::setPeriod,
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    BigNumber(stringResource(Res.string.stat_downloaded), Formatters.bytes(totals.downloaded, u), Modifier.weight(1f))
                    BigNumber(
                        stringResource(Res.string.stat_uploaded),
                        Formatters.bytes(totals.uploaded, u) + if (totals.uploaded == 0L) " ✓" else "",
                        Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    BigNumber(stringResource(Res.string.stat_completed), totals.completed.toString(), Modifier.weight(1f))
                    BigNumber(stringResource(Res.string.stat_peak), Formatters.speed(totals.peakRate, u), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    BigNumber(stringResource(Res.string.stat_active_time), Formatters.duration(totals.activeSeconds, u), Modifier.weight(1f))
                    BigNumber(stringResource(Res.string.stat_added), totals.added.toString(), Modifier.weight(1f))
                }
            }
        }
        item {
            GraphiteCard {
                SectionHeader(stringResource(Res.string.live_speed))
                SpeedChart(
                    values = speed.map { it.downloadRate },
                    height = 140.dp,
                    contentDescription = stringResource(Res.string.speed_chart_description),
                    valueLabel = { _, v -> Formatters.speed(v, u) },
                )
                Spacer(Modifier.height(spacing.xs))
                Text(
                    stringResource(Res.string.live_speed_summary, Formatters.speed(session.downloadRate, u), Formatters.bytes(session.sessionDownloaded, u)),
                    style = GraphiteTheme.typography.numberCaption,
                    color = GraphiteTheme.colors.textSecondary,
                )
            }
        }
        if (period != StatsPeriod.DAY) {
            item {
                GraphiteCard {
                    SectionHeader(stringResource(Res.string.downloaded_per_day))
                    BarChart(
                        bars = days.map { BarValue(LocalDate.ofEpochDay(it.day).dayOfMonth.toString(), it.downloaded) },
                        valueLabel = { Formatters.bytes(it, u) },
                    )
                }
            }
        }
        item { SectionHeader(stringResource(Res.string.history)) }
        if (history.isEmpty()) {
            item { Text(stringResource(Res.string.history_empty), style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary) }
        }
        items(history, key = { it.id }) { h ->
            DetailRow(
                title = h.name,
                subtitle = stringResource(
                    when (h.event) {
                        HistoryEvent.ADDED -> Res.string.history_added
                        HistoryEvent.COMPLETED -> Res.string.history_completed
                        HistoryEvent.REMOVED -> Res.string.history_removed
                    },
                ) + " · " + formatDate(h.timestamp),
                value = Formatters.bytes(h.size, u),
                icon = when (h.event) {
                    HistoryEvent.ADDED -> GraphiteIcons.Plus
                    HistoryEvent.COMPLETED -> GraphiteIcons.Check
                    HistoryEvent.REMOVED -> GraphiteIcons.Trash2
                },
            )
        }
    }
}

@Composable
private fun BigNumber(label: String, value: String, modifier: Modifier = Modifier) {
    GraphiteCard(modifier) {
        Text(label, style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
        Spacer(Modifier.height(GraphiteTheme.spacing.xxs))
        Text(value, style = GraphiteTheme.typography.numberLarge, maxLines = 1)
    }
}

