package com.example.minutewatcher.core.model.util

import org.junit.Assert.*
import org.junit.Test
import java.util.*

class DateUtilsTest {

    @Test
    fun testShouldPerformAutoReset_BeforeResetDay() {
        // Сегодня 5 Октября, день сброса 15-е
        val now = createDate(Calendar.OCTOBER, 5)
        
        // Сброс уже был в этом цикле (начался 15 Сентября)
        val lastResetDate = createDate(Calendar.SEPTEMBER, 16) 
        assertFalse(DateUtils.shouldPerformAutoReset(lastResetDate, 15, now))
        
        // Сброс был до начала цикла (до 15 Сентября)
        val lastResetOld = createDate(Calendar.SEPTEMBER, 10)
        assertTrue(DateUtils.shouldPerformAutoReset(lastResetOld, 15, now))
    }

    @Test
    fun testShouldPerformAutoReset_AfterResetDay() {
        // Сегодня 20 Октября, день сброса 15-е
        val now = createDate(Calendar.OCTOBER, 20)
        
        // Сброс уже был в этом цикле (начался 15 Октября)
        val lastResetDate = createDate(Calendar.OCTOBER, 16)
        assertFalse(DateUtils.shouldPerformAutoReset(lastResetDate, 15, now))
        
        // Сброс был до начала цикла (например, 10 Октября)
        val lastResetOld = createDate(Calendar.OCTOBER, 10)
        assertTrue(DateUtils.shouldPerformAutoReset(lastResetOld, 15, now))
    }

    @Test
    fun testShouldPerformAutoReset_OnResetDay() {
        // Сегодня 15 Октября, день сброса 15-е
        val now = createDate(Calendar.OCTOBER, 15, hour = 10)
        
        // Сброс был вчера (в прошлом цикле)
        val lastResetYesterday = createDate(Calendar.OCTOBER, 14)
        assertTrue(DateUtils.shouldPerformAutoReset(lastResetYesterday, 15, now))
        
        // Сброс был сегодня в 00:01
        val lastResetToday = createDate(Calendar.OCTOBER, 15, hour = 0, minute = 1)
        assertFalse(DateUtils.shouldPerformAutoReset(lastResetToday, 15, now))
    }

    private fun createDate(month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return Calendar.getInstance().apply {
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
