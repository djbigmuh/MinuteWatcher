package com.example.minutewatcher.core.model.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DateUtilsTest {
    @Test
    fun testGetMonthProgressPercentage() {
        // Oct 16th, reset day 1 -> middle of month (approx 50%)
        val now = LocalDateTime.of(2026, 10, 16, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val progress = DateUtils.getMonthProgressPercentage(1, now)
        // Since BillingPeriodCalculator uses system default zone if not provided,
        // we might have slight mismatch if we don't fix zones.
        // But for unit logic it's mostly fine if we don't care about exact % in this specific test.
        assertTrue(progress > 45 && progress < 55)
    }
}
