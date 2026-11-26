package com.graphite.torrent.core.schedule

import com.graphite.torrent.core.model.ScheduleAction
import com.graphite.torrent.core.model.ScheduleDecision
import com.graphite.torrent.core.model.ScheduleRule
import java.time.DayOfWeek
import java.time.LocalDateTime

/** Pure evaluation of scheduler rules for a given local time. */
object ScheduleEvaluator {

    fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    /** True when [rule] covers [time]. Windows that cross midnight belong to the day they start on. */
    fun isActive(rule: ScheduleRule, time: LocalDateTime): Boolean {
        if (!rule.enabled) return false
        val minute = time.hour * 60 + time.minute
        val today = dayBit(time.dayOfWeek)
        val yesterday = dayBit(time.dayOfWeek.minus(1))
        val start = rule.startMinute.coerceIn(0, 1439)
        val end = rule.endMinute.coerceIn(0, 1440)
        return if (end > start) {
            rule.days and today != 0 && minute in start until end
        } else {
            // Crosses midnight (or covers the whole day when start == end).
            (rule.days and today != 0 && minute >= start) || (rule.days and yesterday != 0 && minute < end)
        }
    }

    /**
     * Combines all active rules: any PAUSE wins; the strictest non-zero limits are used;
     * the first PROFILE rule (by start time) picks the profile.
     */
    fun decide(rules: List<ScheduleRule>, time: LocalDateTime): ScheduleDecision {
        val active = rules.filter { isActive(it, time) }.sortedBy { it.startMinute }
        if (active.isEmpty()) return ScheduleDecision()
        fun strictest(values: List<Int>): Int? = values.filter { it > 0 }.minOrNull()
        val limits = active.filter { it.action == ScheduleAction.LIMIT }
        return ScheduleDecision(
            pause = active.any { it.action == ScheduleAction.PAUSE },
            downloadLimitKbps = strictest(limits.map { it.downloadKbps }),
            uploadLimitKbps = strictest(limits.map { it.uploadKbps }),
            profile = active.firstOrNull { it.action == ScheduleAction.PROFILE }?.profile,
            activeRuleIds = active.map { it.id },
        )
    }
}
