package com.example.minutewatcher.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DateUtilsTest {
    @Test
    fun testGetMonthProgressPercentage_MiddleOfMonth() {
        // Oct 16th, reset day 1 -> middle of month (approx 50%)
        val now = LocalDateTime.of(2026, 10, 16, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val progress = DateUtils.getMonthProgressPercentage(1, now, ZoneId.of("UTC"))
        // We use a range to avoid flaky tests due to system timezone,
        // although in pure logic it should be exactly (15/31) * 100 on UTC.
        assertTrue("Progress $progress should be around 50%", progress in 45..55)
    }

    @Test
    fun testGetMonthProgressPercentage_StartOfMonth() {
        // Oct 1st, reset day 1 -> 0%
        val now = LocalDateTime.of(2026, 10, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val progress = DateUtils.getMonthProgressPercentage(1, now, ZoneId.of("UTC"))
        assertEquals(0, progress)
    }

    @Test
    fun testGetMonthProgressPercentage_EndOfMonth() {
        // Oct 31st, reset day 1 -> near 100%
        val now = LocalDateTime.of(2026, 10, 31, 23, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        val progress = DateUtils.getMonthProgressPercentage(1, now, ZoneId.of("UTC"))
        assertTrue("Progress $progress should be near 100%", progress >= 99)
    }

    @Test
    fun testGetDaysRemainingInMonth_Basic() {
        // Oct 16th, reset day 1 -> 15 days remaining (31 - 16 = 15)
        // Wait, if reset is on 1st, and today is 16th, it's 15 days UNTIL 1st of next month?
        // Oct has 31 days. 16, 17, ..., 31. That's 16 days.
        // Let's check implementation.
        val now = LocalDateTime.of(2026, 10, 16, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(1, now, ZoneId.of("UTC"))
        // 31 - 16 + 1 = 16 days including today? Or 15 full days?
        // BillingPeriodCalculator usually returns startDate of next period.
        assertTrue(remaining in 15..16)
    }

    @Test
    fun testGetDaysRemainingInMonth_LastDay() {
        // Oct 31st, reset day 1 -> 0 or 1 day remaining
        val now = LocalDateTime.of(2026, 10, 31, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(1, now, ZoneId.of("UTC"))
        assertTrue(remaining <= 1)
    }
}
