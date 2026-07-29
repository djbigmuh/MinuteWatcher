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
        // resetDayActual = min(29, 29) = 29.
        // now.dayOfMonth (28) < 29 is true.
        // Prev month Jan (31 days). prevResetDay = min(29, 31) = 29.
        val now = createMillis(2024, 2, 28)
        val info = BillingPeriodCalculator.calculate(29, now, zoneId)

        // Jan has 31 days, so started Jan 29
        assertEquals(createMillis(2024, 1, 29), info.startDate)
        assertEquals(createMillis(2024, 2, 29), info.endDate)
    }

    @Test
    fun testCalculate_NonLeapYear_Feb_Reset31() {
        // Reset day 31, now is March 5 2025
        // currentYearMonth = March (31 days). resetDayActual = min(31, 31) = 31.
        // now.dayOfMonth (5) < 31 is true.
        // Prev month Feb (28 days). prevResetDay = min(31, 28) = 28.
        val now = createMillis(2025, 3, 5)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        // Feb 2025 has 28 days, so started Feb 28
        assertEquals(createMillis(2025, 2, 28), info.startDate)
        // End is March 31
        assertEquals(createMillis(2025, 3, 31), info.endDate)
    }

    @Test
    fun testCalculate_31stOnShortMonth() {
        // Reset day 31, now is April 30
        // April has 30 days. resetDayActual = min(31, 30) = 30.
        // now.dayOfMonth (30) < 30 is false.
        // Period started THIS month (April 30).
        val now = createMillis(2026, 4, 30)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        assertEquals(createMillis(2026, 4, 30), info.startDate)
        // End is May 31
        assertEquals(createMillis(2026, 5, 31), info.endDate)
    }

    @Test
    fun testCalculate_31stOnMay1st() {
        // Reset day 31, now is May 1st
        // May has 31 days. resetDayActual = 31.
        // 1 < 31 is true.
        // Prev month April (30 days). prevResetDay = min(31, 30) = 30.
        val now = createMillis(2026, 5, 1)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

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
    fun testCalculate_LeapYear_Feb29_Start() {
        // Reset day 31, now is Feb 29 2024 (Leap year)
        // Feb has 29 days. resetDayActual = 29.
        // 29 < 29 is false.
        // Starts Feb 29.
        val now = createMillis(2024, 2, 29)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        assertEquals(createMillis(2024, 2, 29), info.startDate)
        // End is March 31
        assertEquals(createMillis(2024, 3, 31), info.endDate)
    }

    @Test
    fun testCalculate_31stOnFeb28th_NonLeap() {
        // Reset day 31, now is Feb 28 2026
        // Feb has 28 days. resetDayActual = 28.
        // 28 < 28 is false.
        // Starts Feb 28.
        val now = createMillis(2026, 2, 28)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        assertEquals(createMillis(2026, 2, 28), info.startDate)
        // Ends March 31
        assertEquals(createMillis(2026, 3, 31), info.endDate)
    }

    @Test
    fun testProgress_CorrectOnShortMonth() {
        // Reset 31, now is Feb 28 (non-leap)
        // Period is Feb 28 to March 31.
        // Total days: 1 (Feb 28) + 31 (March) = 32 days.
        val now = createMillis(2026, 2, 28)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        assertEquals(0, info.progressPercentage)

        // Move to March 16
        val middleNow = createMillis(2026, 3, 16)
        val middleInfo = BillingPeriodCalculator.calculate(31, middleNow, zoneId)
        // Elapsed: 1 (Feb 28) + 15 (March 1-16) = 16 days.
        // Period: Feb 28 to March 31 = 31 days.
        // 16 / 31 = 51%
        assertEquals(51, middleInfo.progressPercentage)
    }

    @Test
    fun testCalculate_31stOnFeb29th_Leap() {
        // Reset 31, now is Feb 29 2024 (Leap)
        // lengthOfMonth = 29. resetDayActual = 29.
        // 29 < 29 is false.
        // Starts Feb 29.
        val now = createMillis(2024, 2, 29)
        val info = BillingPeriodCalculator.calculate(31, now, zoneId)

        assertEquals(createMillis(2024, 2, 29), info.startDate)
        // End is March 31
        assertEquals(createMillis(2024, 3, 31), info.endDate)
    }

    private fun createMillis(
        year: Int,
        month: Int,
        day: Int,
    ): Long = LocalDateTime.of(year, month, day, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
}
