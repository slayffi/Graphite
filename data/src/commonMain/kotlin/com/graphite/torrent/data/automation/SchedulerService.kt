package com.graphite.torrent.data.automation

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.model.ScheduleAction
import com.graphite.torrent.core.model.ScheduleDecision
import com.graphite.torrent.core.model.ScheduleRule
import com.graphite.torrent.core.schedule.ScheduleEvaluator
import com.graphite.torrent.core.settings.SpeedProfile
import com.graphite.torrent.data.LocalTorrentController
import com.graphite.torrent.data.db.GraphiteDatabase
import com.graphite.torrent.data.repo.EventLogRepository
import com.graphite.torrent.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** Stores schedule rules and applies them every half minute. */
class SchedulerService(
    private val db: GraphiteDatabase,
    private val settings: SettingsRepository,
    private val controller: LocalTorrentController,
    private val eventLog: EventLogRepository,
    private val scope: CoroutineScope,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val q get() = db.scheduleQueries
    private val _decision = MutableStateFlow(ScheduleDecision())
    val decision: StateFlow<ScheduleDecision> = _decision.asStateFlow()
    private var job: Job? = null

    val rules: Flow<List<ScheduleRule>> = q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map {
            ScheduleRule(
                id = it.id,
                days = it.days.toInt(),
                startMinute = it.start_minute.toInt(),
                endMinute = it.end_minute.toInt(),
                action = runCatching { ScheduleAction.valueOf(it.action) }.getOrDefault(ScheduleAction.PAUSE),
                downloadKbps = it.download_kbps.toInt(),
                uploadKbps = it.upload_kbps.toInt(),
                profile = it.profile?.let { p -> runCatching { SpeedProfile.valueOf(p) }.getOrNull() },
                enabled = it.enabled,
            )
        }
    }

    suspend fun all(): List<ScheduleRule> = withContext(Dispatchers.IO) {
        q.selectAll().executeAsList().map {
            ScheduleRule(
                it.id, it.days.toInt(), it.start_minute.toInt(), it.end_minute.toInt(),
                runCatching { ScheduleAction.valueOf(it.action) }.getOrDefault(ScheduleAction.PAUSE),
                it.download_kbps.toInt(), it.upload_kbps.toInt(),
                it.profile?.let { p -> runCatching { SpeedProfile.valueOf(p) }.getOrNull() }, it.enabled,
            )
        }
    }

    suspend fun save(rule: ScheduleRule) = withContext(Dispatchers.IO) {
        if (rule.id == 0L) {
            q.insertRule(rule.days.toLong(), rule.startMinute.toLong(), rule.endMinute.toLong(), rule.action.name,
                rule.downloadKbps.toLong(), rule.uploadKbps.toLong(), rule.profile?.name, rule.enabled)
        } else {
            q.updateRule(rule.days.toLong(), rule.startMinute.toLong(), rule.endMinute.toLong(), rule.action.name,
                rule.downloadKbps.toLong(), rule.uploadKbps.toLong(), rule.profile?.name, rule.enabled, rule.id)
        }
        tick()
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        q.deleteRule(id)
        tick()
    }

    suspend fun replaceAll(rules: List<ScheduleRule>) = withContext(Dispatchers.IO) {
        db.transaction {
            q.deleteAll()
            rules.forEach { r ->
                q.insertRule(r.days.toLong(), r.startMinute.toLong(), r.endMinute.toLong(), r.action.name,
                    r.downloadKbps.toLong(), r.uploadKbps.toLong(), r.profile?.name, r.enabled)
            }
        }
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(30_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Applies the current decision; safe to call any time. */
    suspend fun tick() {
        val enabled = settings.current().schedulerEnabled
        val decision = if (enabled) ScheduleEvaluator.decide(all(), now()) else ScheduleDecision()
        val previous = _decision.value
        _decision.value = decision
        controller.setPauseReason(PauseReason.SCHEDULE, decision.pause)
        controller.setOverrides(decision.profile, decision.downloadLimitKbps, decision.uploadLimitKbps)
        if (decision != previous && (decision.activeRuleIds.isNotEmpty() || previous.activeRuleIds.isNotEmpty())) {
            val text = buildList {
                if (decision.pause) add("pause")
                decision.downloadLimitKbps?.let { add("↓ $it KB/s") }
                decision.profile?.let { add(it.name.lowercase()) }
                if (isEmpty()) add("off")
            }.joinToString(", ")
            eventLog.log(EventType.SCHEDULE_APPLIED, text)
        }
    }
}
