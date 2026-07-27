package com.example.minutewatcher.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class BillingPeriodCalculatorTest {
    private val zoneId = ZoneId.of("UTC")

    @Test
    fun testCalculate_StartOfMonth() {
        // Reset day 1, now is Jan 10
        val now = createMillis(2026, 1, 10)
        val info = BillingPeriodCalculator.calculate(1, now, zoneId)

        assertEquals(createMillis(2026, 1, 1), info.startDate)
        // End is Feb 1, 00:00:00.000
        assertEquals(createMillis(2026, 2, 1), info.endDate)
    }

    @Test
    fun testCalculate_MidMonth() {
        // Reset day 15, now is Jan 20
        val now = createMillis(2026, 1, 20)
        val info = BillingPeriodCalculator.calculate(15, now, zoneId)

        assertEquals(createMillis(2026, 1, 15), info.startDate)
        assertEquals(createMillis(2026, 2, 15), info.endDate)
    }

    @Test
    fun testCalculate_PreviousMonth() {
        // Reset day 15, now is Jan 10
        val now = createMillis(2026, 1, 10)
        val info = BillingPeriodCalculator.calculate(15, now, zoneId)

        // Should start Dec 15
        assertEquals(createMillis(2025, 12, 15), info.startDate)
        assertEquals(createMillis(2026, 1, 15), info.endDate)
    }

    @Test
    fun testCalculate_LeapYear_Feb() {
        // Reset day 29, now is Feb 28 2024 (Leap year)
        val now = createMillis(2024, 2, 28)
        val info = BillingPeriodCalculator.calculate(29, now, zoneId)

        // Jan has 31 days, so started Jan 29
        assertEquals(createMillis(2024, 1, 29), info.startDate)
        assertEquals(createMillis(2024, 2, 29), info.endDate)
    }

    @Test
    fun testCalculate_NonLeapYear_Feb() {
        // Reset day 31, now is March 5 2025
        val now = createMillis(2025, 3, 5)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        // Feb 2025 has 28 days, so started Feb 28
        assertEquals(createMillis(2025, 2, 28), info.startDate)
    }

    @Test
    fun testCalculate_31stOnShortMonth() {
        // Reset day 31, now is April 30
        val now = createMillis(2026, 4, 30)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        // March has 31 days, so started March 31
        assertEquals(createMillis(2026, 3, 31), info.startDate)
        // End is April 30 (since April only has 30 days)
        assertEquals(createMillis(2026, 4, 30), info.endDate)
    }

    @Test
    fun testCalculate_31stOnMay1st() {
        // Reset day 31, now is May 1st
        val now = createMillis(2026, 5, 1)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        // April has 30 days.
        // 1 < 31 is true. Prev month April (30 days). 
        // Started April 30.
        assertEquals(createMillis(2026, 4, 30), info.startDate)
        // End is May 31
        assertEquals(createMillis(2026, 5, 31), info.endDate)
    }

    @Test
    fun testCalculate_CrossingYearBoundary() {
        // Reset day 25, now is Jan 5 2026
        val now = createMillis(2026, 1, 5)
        val info = BillingPeriodCalculator.calculate(25, now, zoneId)

        // Started Dec 25 2025
        assertEquals(createMillis(2025, 12, 25), info.startDate)
        // Ends Jan 25 2026
        assertEquals(createMillis(2026, 1, 25), info.endDate)
    }

    @Test
    fun testCalculate_NewYearTransition() {
        // Reset day 1, now is Jan 1st 2026 00:05
        val now = LocalDateTime.of(2026, 1, 1, 0, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        val info = BillingPeriodCalculator.calculate(1, now, zoneId)

        assertEquals(createMillis(2026, 1, 1), info.startDate)
        assertEquals(createMillis(2026, 2, 1), info.endDate)
    }

    @Test
    fun testCalculate_LeapYear_Feb29_End() {
        // Reset day 31, now is March 1st 2024 (Leap year)
        val now = createMillis(2024, 3, 1)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        // Feb 2024 has 29 days. Reset day 31 -> Feb 29.
        assertEquals(createMillis(2024, 2, 29), info.startDate)
    }

    @Test
    fun testCalculate_31stInFebruary() {
        // Reset day 31, now is Feb 15 2026 (Non-leap year)
        val now = createMillis(2026, 2, 15)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        // Started Jan 31
        assertEquals(createMillis(2026, 1, 31), info.startDate)
        // Ends Feb 28
        assertEquals(createMillis(2026, 2, 28), info.endDate)
    }

    @Test
    fun testCalculate_31stOnFeb28th() {
        // Reset day 31, now is Feb 28 2026
        val now = createMillis(2026, 2, 28)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        // Started Jan 31.
        assertEquals(createMillis(2026, 1, 31), info.startDate)
        // Ends Feb 28
        assertEquals(createMillis(2026, 2, 28), info.endDate)
    }

    private fun createMillis(
        year: Int,
        month: Int,
        day: Int,
    ): Long = LocalDateTime.of(year, month, day, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
}
