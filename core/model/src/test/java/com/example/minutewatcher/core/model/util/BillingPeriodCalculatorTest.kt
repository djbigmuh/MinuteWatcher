package com.example.minutewatcher.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.*

class BillingPeriodCalculatorTest {

    @Test
    fun testCalculate_StartOfMonth() {
        // Reset day 1, now is Jan 10
        val now = createDate(2026, Calendar.JANUARY, 10)
        val info = BillingPeriodCalculator.calculate(1, now.timeInMillis)
        
        assertEquals(createDate(2026, Calendar.JANUARY, 1).timeInMillis, info.startDate)
        // End is Feb 1, 00:00:00.000
        assertEquals(createDate(2026, Calendar.FEBRUARY, 1).timeInMillis, info.endDate)
    }

    @Test
    fun testCalculate_MidMonth() {
        // Reset day 15, now is Jan 20
        val now = createDate(2026, Calendar.JANUARY, 20)
        val info = BillingPeriodCalculator.calculate(15, now.timeInMillis)
        
        assertEquals(createDate(2026, Calendar.JANUARY, 15).timeInMillis, info.startDate)
        assertEquals(createDate(2026, Calendar.FEBRUARY, 15).timeInMillis, info.endDate)
    }

    @Test
    fun testCalculate_PreviousMonth() {
        // Reset day 15, now is Jan 10
        val now = createDate(2026, Calendar.JANUARY, 10)
        val info = BillingPeriodCalculator.calculate(15, now.timeInMillis)
        
        // Should start Dec 15
        assertEquals(createDate(2025, Calendar.DECEMBER, 15).timeInMillis, info.startDate)
        assertEquals(createDate(2026, Calendar.JANUARY, 15).timeInMillis, info.endDate)
    }

    @Test
    fun testCalculate_LeapYear_Feb() {
        // Reset day 29, now is Feb 28 2024 (Leap year)
        val now = createDate(2024, Calendar.FEBRUARY, 28)
        val info = BillingPeriodCalculator.calculate(29, now.timeInMillis)
        
        // Jan has 31 days, so started Jan 29
        assertEquals(createDate(2024, Calendar.JANUARY, 29).timeInMillis, info.startDate)
        assertEquals(createDate(2024, Calendar.FEBRUARY, 29).timeInMillis, info.endDate)
    }

    @Test
    fun testCalculate_NonLeapYear_Feb() {
        // Reset day 31, now is March 5 2025
        val now = createDate(2025, Calendar.MARCH, 5)
        val info = BillingPeriodCalculator.calculate(31, now.timeInMillis)
        
        // Feb 2025 has 28 days, so started Feb 28
        assertEquals(createDate(2025, Calendar.FEBRUARY, 28).timeInMillis, info.startDate)
    }

    @Test
    fun testCalculate_31stOnShortMonth() {
        // Reset day 31, now is April 30
        val now = createDate(2026, Calendar.APRIL, 30)
        val info = BillingPeriodCalculator.calculate(31, now.timeInMillis)
        
        // March has 31 days, so started March 31
        assertEquals(createDate(2026, Calendar.MARCH, 31).timeInMillis, info.startDate)
        // End is April 30 (since April only has 30 days)
        assertEquals(createDate(2026, Calendar.APRIL, 30).timeInMillis, info.endDate)
    }

    @Test
    fun testCalculate_31stOnMay1st() {
        // Reset day 31, now is May 1st
        val now = createDate(2026, Calendar.MAY, 1)
        val info = BillingPeriodCalculator.calculate(31, now.timeInMillis)
        
        // April has 30 days, resetDayActual for May is 31.
        // 1 < 31 is true. Prev month April (30 days). prevResetDay = 30.
        // Started April 30.
        assertEquals(createDate(2026, Calendar.APRIL, 30).timeInMillis, info.startDate)
        // End is May 31
        assertEquals(createDate(2026, Calendar.MAY, 31).timeInMillis, info.endDate)
    }

    @Test
    fun testCalculate_CrossingYearBoundary() {
        // Reset day 25, now is Jan 5 2026
        val now = createDate(2026, Calendar.JANUARY, 5)
        val info = BillingPeriodCalculator.calculate(25, now.timeInMillis)
        
        // Started Dec 25 2025
        assertEquals(createDate(2025, Calendar.DECEMBER, 25).timeInMillis, info.startDate)
        // Ends Jan 25 2026
        assertEquals(createDate(2026, Calendar.JANUARY, 25).timeInMillis, info.endDate)
    }

    @Test
    fun testCalculate_NewYearTransition() {
        // Reset day 1, now is Jan 1st 2026 00:05
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 1, 0, 5, 0)
        }
        val info = BillingPeriodCalculator.calculate(1, now.timeInMillis)
        
        assertEquals(createDate(2026, Calendar.JANUARY, 1).timeInMillis, info.startDate)
        assertEquals(createDate(2026, Calendar.FEBRUARY, 1).timeInMillis, info.endDate)
    }

    @Test
    fun testCalculate_LeapYear_Feb29_End() {
        // Reset day 31, now is March 1st 2024 (Leap year)
        val now = createDate(2024, Calendar.MARCH, 1)
        val info = BillingPeriodCalculator.calculate(31, now.timeInMillis)
        
        // Feb 2024 has 29 days. Reset day 31 -> Feb 29.
        assertEquals(createDate(2024, Calendar.FEBRUARY, 29).timeInMillis, info.startDate)
    }

    @Test
    fun testCalculate_31stInFebruary() {
        // Reset day 31, now is Feb 15 2026 (Non-leap year)
        val now = createDate(2026, Calendar.FEBRUARY, 15)
        val info = BillingPeriodCalculator.calculate(31, now.timeInMillis)
        
        // Started Jan 31
        assertEquals(createDate(2026, Calendar.JANUARY, 31).timeInMillis, info.startDate)
        // Ends Feb 28
        assertEquals(createDate(2026, Calendar.FEBRUARY, 28).timeInMillis, info.endDate)
    }

    @Test
    fun testCalculate_31stOnFeb28th() {
        // Reset day 31, now is Feb 28 2026
        val now = createDate(2026, Calendar.FEBRUARY, 28)
        val info = BillingPeriodCalculator.calculate(31, now.timeInMillis)
        
        // Jan has 31 days. So 28 < 31 is true.
        // Started Jan 31.
        assertEquals(createDate(2026, Calendar.JANUARY, 31).timeInMillis, info.startDate)
        // Ends Feb 28
        assertEquals(createDate(2026, Calendar.FEBRUARY, 28).timeInMillis, info.endDate)
    }

    private fun createDate(year: Int, month: Int, day: Int): Calendar {
        return Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
