package com.graphite.torrent.ui.app.automation

import androidx.compose.foundation.layout.Arrangement
import com.graphite.torrent.ui.app.common.rememberAppScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graphite.torrent.core.model.ScheduleAction
import com.graphite.torrent.core.model.ScheduleRule
import com.graphite.torrent.core.settings.SpeedProfile
import com.graphite.torrent.data.automation.SchedulerService
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.ButtonKind
import com.graphite.torrent.ui.designsystem.components.ButtonRow
import com.graphite.torrent.ui.designsystem.components.DetailRow
import com.graphite.torrent.ui.designsystem.components.FilterChip
import com.graphite.torrent.ui.designsystem.components.GraphiteButton
import com.graphite.torrent.ui.designsystem.components.GraphiteCard
import com.graphite.torrent.ui.designsystem.components.GraphiteIconButton
import com.graphite.torrent.ui.designsystem.components.GraphiteSheet
import com.graphite.torrent.ui.designsystem.components.GraphiteTextField
import com.graphite.torrent.ui.designsystem.components.SectionHeader
import com.graphite.torrent.ui.designsystem.components.SegmentedControl
import com.graphite.torrent.ui.designsystem.components.SwitchSettingRow
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val dayNames: List<StringResource> = listOf(
    Res.string.day_mon, Res.string.day_tue, Res.string.day_wed, Res.string.day_thu,
    Res.string.day_fri, Res.string.day_sat, Res.string.day_sun,
)

internal fun minutesText(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)

@Composable
private fun daysText(days: Int): String = when (days) {
    ScheduleRule.ALL_DAYS -> stringResource(Res.string.days_every)
    ScheduleRule.WORKDAYS -> stringResource(Res.string.days_workdays)
    ScheduleRule.WEEKEND -> stringResource(Res.string.days_weekend)
    else -> dayNames.withIndex().filter { days and (1 shl it.index) != 0 }.map { stringResource(it.value) }.joinToString(", ")
}

@Composable
private fun actionText(rule: ScheduleRule): String = when (rule.action) {
    ScheduleAction.PAUSE -> stringResource(Res.string.sched_action_pause)
    ScheduleAction.LIMIT -> stringResource(Res.string.sched_limit_value, rule.downloadKbps, rule.uploadKbps)
    ScheduleAction.PROFILE -> stringResource(Res.string.sched_action_profile) + ": " + profileName(rule.profile ?: SpeedProfile.TURBO)
}

@Composable
private fun profileName(p: SpeedProfile) = stringResource(
    when (p) {
        SpeedProfile.TURBO -> Res.string.profile_turbo
        SpeedProfile.BALANCED -> Res.string.profile_balanced
        SpeedProfile.ECONOMY -> Res.string.profile_economy
    },
)

@Composable
fun SchedulerScreen(env: AppEnvironment, scheduler: SchedulerService, onBack: () -> Unit) {
    val rules by scheduler.rules.collectAsStateWithLifecycle(emptyList())
    val settings by env.settings.settings.collectAsStateWithLifecycle()
    val decision by scheduler.decision.collectAsStateWithLifecycle()
    val scope = rememberAppScope()
    var editing by remember { mutableStateOf<ScheduleRule?>(null) }
    val spacing = GraphiteTheme.spacing

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            GraphiteIconButton(GraphiteIcons.ArrowLeft, stringResource(Res.string.back), onBack)
            Text(stringResource(Res.string.scheduler_title), style = GraphiteTheme.typography.screenTitle, modifier = Modifier.weight(1f).padding(start = spacing.xs))
            GraphiteIconButton(GraphiteIcons.Plus, stringResource(Res.string.sched_add), { editing = ScheduleRule(startMinute = 9 * 60, endMinute = 18 * 60) })
        }
        LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            item {
                GraphiteCard(contentPadding = spacing.xxs) {
                    SwitchSettingRow(
                        stringResource(Res.string.sched_enabled),
                        stringResource(Res.string.scheduler_desc),
                        settings.schedulerEnabled,
                        { v -> scope.launch { env.settings.update { it.copy(schedulerEnabled = v) }; scheduler.tick() } },
                        icon = GraphiteIcons.CalendarClock,
                    )
                }
            }
            if (settings.schedulerEnabled) {
                item {
                    val kbps = GraphiteTheme.units.kilobytesPerSecond
                    val now = buildList {
                        if (decision.pause) add(stringResource(Res.string.sched_action_pause))
                        decision.downloadLimitKbps?.let { add("↓ $it " + kbps) }
                        decision.profile?.let { add(profileName(it)) }
                    }
                    Text(
                        stringResource(Res.string.sched_now, now.joinToString(", ").ifEmpty { stringResource(Res.string.sched_nothing) }),
                        style = GraphiteTheme.typography.caption,
                        color = GraphiteTheme.colors.textSecondary,
                    )
                }
            }
            if (rules.isEmpty()) {
                item { Text(stringResource(Res.string.sched_empty), style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary) }
            }
            items(rules, key = { it.id }) { rule ->
                GraphiteCard(onClick = { editing = rule }) {
                    DetailRow(
                        title = "${minutesText(rule.startMinute)}–${minutesText(rule.endMinute)} · ${daysText(rule.days)}",
                        subtitle = actionText(rule) + if (!rule.enabled) " · " + stringResource(Res.string.tracker_disabled) else "",
                        value = "",
                        icon = when (rule.action) {
                            ScheduleAction.PAUSE -> GraphiteIcons.Pause
                            ScheduleAction.LIMIT -> GraphiteIcons.Gauge
                            ScheduleAction.PROFILE -> GraphiteIcons.Zap
                        },
                    )
                }
            }
        }
    }

    editing?.let { rule ->
        RuleEditor(rule, onDismiss = { editing = null }, onSave = { r ->
            scope.launch { scheduler.save(r) }
            editing = null
        }, onDelete = if (rule.id != 0L) ({
            scope.launch { scheduler.delete(rule.id) }
            editing = null
        }) else null)
    }
}

@Composable
private fun RuleEditor(initial: ScheduleRule, onDismiss: () -> Unit, onSave: (ScheduleRule) -> Unit, onDelete: (() -> Unit)?) {
    var rule by remember { mutableStateOf(initial) }
    var start by remember { mutableStateOf(minutesText(initial.startMinute)) }
    var end by remember { mutableStateOf(minutesText(initial.endMinute)) }
    var down by remember { mutableStateOf(initial.downloadKbps.toString()) }
    var up by remember { mutableStateOf(initial.uploadKbps.toString()) }
    val spacing = GraphiteTheme.spacing
    fun parseTime(t: String): Int? {
        val parts = t.trim().split(':', '.')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return if (h in 0..24 && m in 0..59) (h * 60 + m).coerceAtMost(1440) else null
    }
    GraphiteSheet(onDismiss = onDismiss, title = stringResource(if (initial.id == 0L) Res.string.sched_add else Res.string.sched_edit)) {
        SectionHeader(stringResource(Res.string.sched_days))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dayNames.forEachIndexed { i, res ->
                val bit = 1 shl i
                FilterChip(stringResource(res), rule.days and bit != 0, { rule = rule.copy(days = rule.days xor bit) })
            }
        }
        Spacer(Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            GraphiteTextField(start, { start = it }, Modifier.weight(1f), label = stringResource(Res.string.sched_from), keyboardType = KeyboardType.Number)
            GraphiteTextField(end, { end = it }, Modifier.weight(1f), label = stringResource(Res.string.sched_to), keyboardType = KeyboardType.Number)
        }
        Spacer(Modifier.height(spacing.sm))
        SegmentedControl(ScheduleAction.entries, rule.action, label = { a ->
            stringResource(
                when (a) {
                    ScheduleAction.PAUSE -> Res.string.sched_action_pause
                    ScheduleAction.LIMIT -> Res.string.sched_action_limit
                    ScheduleAction.PROFILE -> Res.string.sched_action_profile
                },
            )
        }, onSelect = { rule = rule.copy(action = it) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(spacing.sm))
        when (rule.action) {
            ScheduleAction.LIMIT -> Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                GraphiteTextField(down, { down = it.filter(Char::isDigit) }, Modifier.weight(1f), label = stringResource(Res.string.sched_down_kbps), keyboardType = KeyboardType.Number)
                GraphiteTextField(up, { up = it.filter(Char::isDigit) }, Modifier.weight(1f), label = stringResource(Res.string.sched_up_kbps), keyboardType = KeyboardType.Number)
            }
            ScheduleAction.PROFILE -> SegmentedControl(SpeedProfile.entries, rule.profile ?: SpeedProfile.TURBO, label = { profileName(it) }, onSelect = { rule = rule.copy(profile = it) }, modifier = Modifier.fillMaxWidth())
            ScheduleAction.PAUSE -> Unit
        }
        SwitchSettingRow(stringResource(Res.string.sched_rule_enabled), null, rule.enabled, { rule = rule.copy(enabled = it) })
        Spacer(Modifier.height(spacing.md))
        val startMin = parseTime(start)
        val endMin = parseTime(end)
        ButtonRow(Modifier.fillMaxWidth()) {
            if (onDelete != null) GraphiteButton(stringResource(Res.string.delete), onDelete, kind = ButtonKind.DANGER)
            Spacer(Modifier.width(0.dp).weight(1f))
            GraphiteButton(stringResource(Res.string.cancel), onDismiss, kind = ButtonKind.SECONDARY)
            GraphiteButton(stringResource(Res.string.save), {
                onSave(
                    rule.copy(
                        startMinute = startMin ?: rule.startMinute,
                        endMinute = endMin ?: rule.endMinute,
                        downloadKbps = down.toIntOrNull() ?: 0,
                        uploadKbps = up.toIntOrNull() ?: 0,
                        profile = if (rule.action == ScheduleAction.PROFILE) rule.profile ?: SpeedProfile.TURBO else rule.profile,
                    ),
                )
            }, enabled = startMin != null && endMin != null && rule.days != 0)
        }
    }
}
