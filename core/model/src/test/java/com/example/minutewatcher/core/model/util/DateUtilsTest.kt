package com.example.minutewatcher.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class DateUtilsTest {
    private val zoneId = ZoneId.of("UTC")

    @Test
    fun `get days remaining in month basic`() {
        // Current date: 2026-10-16, reset day: 1
        // Next period start date: 2026-11-01
        // Days from 2026-10-16 to 2026-11-01: 16 days
        val now = LocalDateTime.of(2026, 10, 16, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(1, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 11, 1))
        assertEquals(expected.toInt(), remaining)
        assertEquals(16, remaining)
    }

    @Test
    fun `get days remaining in month last day`() {
        // Current date: 2026-10-31, reset day: 1
        // Next period start date: 2026-11-01
        // Days from 2026-10-31 to 2026-11-01: 1 day
        val now = LocalDateTime.of(2026, 10, 31, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(1, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1))
        assertEquals(expected.toInt(), remaining)
        assertEquals(1, remaining)
    }

    @Test
    fun `get days remaining in month reset day 15 mid Feb`() {
        // Current date: 2026-02-20, reset day: 15
        // Next period start date: 2026-03-15
        // Days from 2026-02-20 to 2026-03-15: 23 days
        val now = LocalDateTime.of(2026, 2, 20, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(15, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 2, 20), LocalDate.of(2026, 3, 15))
        assertEquals(expected.toInt(), remaining)
        assertEquals(23, remaining)
    }

    @Test
    fun `get days remaining in month reset day 31 April end`() {
        // Current date: 2026-04-30, reset day: 31
        // Period started on 2026-04-30, next period start date: 2026-05-31
        // Days from 2026-04-30 to 2026-05-31: 31 days
        val now = LocalDateTime.of(2026, 4, 30, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(31, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 31))
        assertEquals(expected.toInt(), remaining)
        assertEquals(31, remaining)
    }

    @Test
    fun `get days remaining in month reset day 30 early Feb`() {
        // Current date: 2026-02-10, reset day: 30
        // Period started on 2026-01-30, next period start date: 2026-02-28
        // Days from 2026-02-10 to 2026-02-28: 18 days
        val now = LocalDateTime.of(2026, 2, 10, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(30, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 2, 10), LocalDate.of(2026, 2, 28))
        assertEquals(expected.toInt(), remaining)
        assertEquals(18, remaining)
    }

    @Test
    fun `get days remaining in month first day of period`() {
        // Current date: 2026-10-01, reset day: 1
        // Period started on 2026-10-01, next period start date: 2026-11-01
        // Days from 2026-10-01 to 2026-11-01: 31 days
        val now = LocalDateTime.of(2026, 10, 1, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(1, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))
        assertEquals(expected.toInt(), remaining)
        assertEquals(31, remaining)
    }
}
