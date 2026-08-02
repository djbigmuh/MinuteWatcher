package com.example.minutewatcher.core.data.util

import android.content.Context
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SimUtilsTest {

    @Test
    fun `getOperatorIconResource returns correct icons for known tokens`() {
        assertEquals(R.drawable.ic_mts, SimUtils.getOperatorIconResource("MTS RUS"))
        assertEquals(R.drawable.ic_beeline, SimUtils.getOperatorIconResource("Beeline"))
        assertEquals(R.drawable.ic_megafon, SimUtils.getOperatorIconResource("MegaFon Corp"))
        assertEquals(R.drawable.ic_tele2, SimUtils.getOperatorIconResource("Tele2 Russia"))
        assertEquals(R.drawable.ic_tele2, SimUtils.getOperatorIconResource("T2"))
        assertEquals(R.drawable.ic_mgts, SimUtils.getOperatorIconResource("MGTS Mobile"))
        assertEquals(R.drawable.ic_yota, SimUtils.getOperatorIconResource("Yota LTE"))
        assertEquals(R.drawable.ic_unknown, SimUtils.getOperatorIconResource("Unknown"))
    }

    @Test
    fun `getOperatorIconResource is case insensitive`() {
        assertEquals(R.drawable.ic_mts, SimUtils.getOperatorIconResource("mts"))
    }

    @Test
    fun `getOperatorIconResource handles delimiters correctly`() {
        assertEquals(R.drawable.ic_mts, SimUtils.getOperatorIconResource("MTS-RUS"))
        assertEquals(R.drawable.ic_mts, SimUtils.getOperatorIconResource("MTS_RUS"))
    }

    @Test
    fun `isInactiveSim returns true for empty or null or 'null' string`() {
        assertTrue(SimUtils.isInactiveSim(null))
        assertTrue(SimUtils.isInactiveSim(""))
        assertTrue(SimUtils.isInactiveSim("null"))
        assertTrue(SimUtils.isInactiveSim("NULL"))
        assertFalse(SimUtils.isInactiveSim("MTS"))
    }

    @Test
    fun `getShortOperatorName truncates long names`() {
        val context: Context = mock()
        // Mocking localized context is tricky, SimUtils has a private method for it.
        // But the truncate logic is at the end of the 'when' block if no token matches.
        
        val longName = "This is a very long operator name that should be truncated"
        val result = SimUtils.getShortOperatorName(context, longName)
        
        assertEquals(20, result.length)
        assertTrue(result.endsWith("..."))
    }
}
