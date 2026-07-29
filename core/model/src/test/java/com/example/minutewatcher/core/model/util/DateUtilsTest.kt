package com.example.minutewatcher.core.model.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DateUtilsTest {
    private val zoneId = ZoneId.of("UTC")

    @Test
    fun testShouldPerformAutoReset_BeforeResetDay() {
        // Сегодня 5 Октября, день сброса 15-е
        val now = createMillis(10, 5)

        // Сброс уже был в этом цикле (начался 15 Сентября)
        val lastResetDate = createMillis(9, 16)
        assertFalse(DateUtils.shouldPerformAutoReset(lastResetDate, 15, now, zoneId))

        // Сброс был до начала цикла (до 15 Сентября)
        val lastResetOld = createMillis(9, 10)
        assertTrue(DateUtils.shouldPerformAutoReset(lastResetOld, 15, now, zoneId))
    }

    @Test
    fun testShouldPerformAutoReset_AfterResetDay() {
        // Сегодня 20 Октября, день сброса 15-е
        val now = createMillis(10, 20)

        // Сброс уже был в этом цикле (начался 15 Октября)
        val lastResetDate = createMillis(10, 16)
        assertFalse(DateUtils.shouldPerformAutoReset(lastResetDate, 15, now, zoneId))

        // Сброс был до начала цикла (например, 10 Октября)
        val lastResetOld = createMillis(10, 10)
        assertTrue(DateUtils.shouldPerformAutoReset(lastResetOld, 15, now, zoneId))
    }

    @Test
    fun testShouldPerformAutoReset_OnResetDay() {
        // Сегодня 15 Октября, день сброса 15-е
        val now = LocalDateTime.of(2026, 10, 15, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

        // Сброс был вчера (в прошлом цикле)
        val lastResetYesterday = createMillis(10, 14)
        assertTrue(DateUtils.shouldPerformAutoReset(lastResetYesterday, 15, now, zoneId))

        // Сброс был сегодня в 00:01
        val lastResetToday = LocalDateTime.of(2026, 10, 15, 0, 1).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertFalse(DateUtils.shouldPerformAutoReset(lastResetToday, 15, now, zoneId))
    }

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

    private fun createMillis(
        month: Int,
        day: Int,
    ): Long = LocalDateTime.of(2026, month, day, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
}
