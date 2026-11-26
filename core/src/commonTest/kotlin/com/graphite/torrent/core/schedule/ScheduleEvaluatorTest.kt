package com.graphite.torrent.core.schedule

import com.graphite.torrent.core.model.ScheduleAction
import com.graphite.torrent.core.model.ScheduleRule
import com.graphite.torrent.core.settings.SpeedProfile
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleEvaluatorTest {
    // 2026-11-23 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, 23 + day, hour, minute)

    @Test
    fun simpleWindowOnWorkdays() {
        val rule = ScheduleRule(1, ScheduleRule.WORKDAYS, 9 * 60, 18 * 60, ScheduleAction.PAUSE)
        assertTrue(ScheduleEvaluator.isActive(rule, at(0, 9)))
        assertTrue(ScheduleEvaluator.isActive(rule, at(4, 17, 59)))
        assertFalse(ScheduleEvaluator.isActive(rule, at(0, 18)))
        assertFalse(ScheduleEvaluator.isActive(rule, at(5, 12)), "Saturday is not a workday")
    }

    @Test
    fun windowAcrossMidnightBelongsToStartDay() {
        val friday = 1 shl 4
        val rule = ScheduleRule(2, friday, 23 * 60, 7 * 60, ScheduleAction.PROFILE, profile = SpeedProfile.TURBO)
        assertTrue(ScheduleEvaluator.isActive(rule, at(4, 23, 30)), "Friday night")
        assertTrue(ScheduleEvaluator.isActive(rule, at(5, 6, 59)), "Saturday early morning")
        assertFalse(ScheduleEvaluator.isActive(rule, at(5, 23, 30)), "Saturday night is not covered")
        assertFalse(ScheduleEvaluator.isActive(rule, at(4, 6)), "Friday early morning belongs to Thursday")
    }

    @Test
    fun disabledRulesNeverApply() {
        assertFalse(ScheduleEvaluator.isActive(ScheduleRule(3, ScheduleRule.ALL_DAYS, 0, 0, enabled = false), at(0, 12)))
        assertTrue(ScheduleEvaluator.isActive(ScheduleRule(4, ScheduleRule.ALL_DAYS, 0, 0), at(2, 12)), "start == end covers the day")
    }

    @Test
    fun combinesRules() {
        val rules = listOf(
            ScheduleRule(1, ScheduleRule.ALL_DAYS, 8 * 60, 20 * 60, ScheduleAction.LIMIT, downloadKbps = 500),
            ScheduleRule(2, ScheduleRule.ALL_DAYS, 12 * 60, 14 * 60, ScheduleAction.LIMIT, downloadKbps = 100, uploadKbps = 10),
            ScheduleRule(3, ScheduleRule.ALL_DAYS, 13 * 60, 15 * 60, ScheduleAction.PROFILE, profile = SpeedProfile.ECONOMY),
        )
        val noon = ScheduleEvaluator.decide(rules, at(1, 13, 30))
        assertFalse(noon.pause)
        assertEquals(100, noon.downloadLimitKbps)
        assertEquals(10, noon.uploadLimitKbps)
        assertEquals(SpeedProfile.ECONOMY, noon.profile)
        assertEquals(listOf(1L, 2L, 3L), noon.activeRuleIds)
        val night = ScheduleEvaluator.decide(rules, at(1, 23))
        assertNull(night.downloadLimitKbps)
        assertTrue(night.activeRuleIds.isEmpty())
        val withPause = ScheduleEvaluator.decide(rules + ScheduleRule(9, ScheduleRule.ALL_DAYS, 0, 0, ScheduleAction.PAUSE), at(1, 9))
        assertTrue(withPause.pause)
    }
}
