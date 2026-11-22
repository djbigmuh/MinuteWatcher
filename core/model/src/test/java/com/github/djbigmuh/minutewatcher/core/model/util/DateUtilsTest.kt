package com.github.djbigmuh.minutewatcher.core.model.util

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
        // Scenario: mid-month date, reset day 1
        val now = LocalDateTime.of(2026, 10, 16, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(1, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 11, 1))
        assertEquals(expected.toInt(), remaining)
        assertEquals(16, remaining)
    }

    @Test
    fun `get days remaining in month last day`() {
        // Scenario: last day of month, reset day 1
        val now = LocalDateTime.of(2026, 10, 31, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(1, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1))
        assertEquals(expected.toInt(), remaining)
        assertEquals(1, remaining)
    }

    @Test
    fun `get days remaining in month reset day 15 mid Feb`() {
        // Scenario: mid February date, reset day 15
        val now = LocalDateTime.of(2026, 2, 20, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(15, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 2, 20), LocalDate.of(2026, 3, 15))
        assertEquals(expected.toInt(), remaining)
        assertEquals(23, remaining)
    }

    @Test
    fun `get days remaining in month reset day 31 April end`() {
        // Scenario: short month end, reset day 31
        val now = LocalDateTime.of(2026, 4, 30, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(31, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 31))
        assertEquals(expected.toInt(), remaining)
        assertEquals(31, remaining)
    }

    @Test
    fun `get days remaining in month reset day 30 early Feb`() {
        // Scenario: early February date, reset day 30
        val now = LocalDateTime.of(2026, 2, 10, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(30, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 2, 10), LocalDate.of(2026, 2, 28))
        assertEquals(expected.toInt(), remaining)
        assertEquals(18, remaining)
    }

    @Test
    fun `get days remaining in month first day of period`() {
        // Scenario: first day of reset period
        val now = LocalDateTime.of(2026, 10, 1, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val remaining = DateUtils.getDaysRemainingInMonth(1, now, zoneId)
        val expected = ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))
        assertEquals(expected.toInt(), remaining)
        assertEquals(31, remaining)
    }
}
