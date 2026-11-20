package com.github.djbigmuh.minutewatcher.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitConversionUtilsTest {
    @Test
    fun whenConvertingBytesToMb_returnsCorrectBinaryMb() {
        assertEquals(0f, UnitConversionUtils.bytesToMb(0L), 0.0001f)
        assertEquals(1.0f, UnitConversionUtils.bytesToMb(1024L * 1024L), 0.0001f)
        assertEquals(1024.0f, UnitConversionUtils.bytesToMb(1024L * 1024L * 1024L), 0.0001f)
        assertEquals(0f, UnitConversionUtils.bytesToMb(-100L), 0.0001f)
    }

    @Test
    fun whenConvertingMbToBytes_returnsCorrectBytes() {
        assertEquals(0L, UnitConversionUtils.mbToBytes(0f))
        assertEquals(1024L * 1024L, UnitConversionUtils.mbToBytes(1.0f))
        assertEquals(0L, UnitConversionUtils.mbToBytes(-1.0f))
    }

    @Test
    fun whenConvertingMbToGbAndGbToMb_maintainsBinaryPrefixConsistency() {
        assertEquals(1.0f, UnitConversionUtils.mbToGb(1024.0f), 0.0001f)
        assertEquals(1024.0f, UnitConversionUtils.gbToMb(1.0f), 0.0001f)
        assertEquals(0.5f, UnitConversionUtils.mbToGb(512.0f), 0.0001f)
    }

    @Test
    fun whenRoundingTrafficMb_roundsToTwoDecimalPlaces() {
        assertEquals(10.55f, UnitConversionUtils.roundTrafficMb(10.548f), 0.0001f)
        assertEquals(10.54f, UnitConversionUtils.roundTrafficMb(10.542f), 0.0001f)
        assertEquals(0f, UnitConversionUtils.roundTrafficMb(-5f), 0.0001f)
    }

    @Test
    fun whenConvertingSecondsToMinutesCeil_roundsUpAndHandlesNonPositive() {
        assertEquals(0, UnitConversionUtils.secondsToMinutesCeil(0))
        assertEquals(0, UnitConversionUtils.secondsToMinutesCeil(-60))
        assertEquals(1, UnitConversionUtils.secondsToMinutesCeil(1))
        assertEquals(1, UnitConversionUtils.secondsToMinutesCeil(60))
        assertEquals(2, UnitConversionUtils.secondsToMinutesCeil(61))
        assertEquals(3, UnitConversionUtils.secondsToMinutesCeil(121))
    }
}
