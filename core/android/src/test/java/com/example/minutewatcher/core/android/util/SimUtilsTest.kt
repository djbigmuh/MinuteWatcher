package com.example.minutewatcher.core.android.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SimUtilsTest {
    private val context: Context = mock()
    private val resources: Resources = mock()
    private val configuration: Configuration = mock()

    @Before
    fun setup() {
        initTestLogger(context)
        whenever(context.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(configuration)
        whenever(context.createConfigurationContext(any())).thenReturn(context)
    }

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
        val longName = "This is a very long operator name that should be truncated"
        val result = SimUtils.getShortOperatorName(context, longName)

        assertEquals(20, result.length)
        assertTrue(result.endsWith("..."))
    }

    @Test
    fun `getOperatorResourceId handles aliases from consolidated map`() {
        // "t2" is an alias for Tele2
        val resId = OperatorResolver.getOperatorResourceId("T2")
        assertEquals(R.string.operator_tele2, resId)
    }

    @Test
    fun `getOperatorResourceId works for newly added operators`() {
        // Rostelecom was recently added to the consolidated map
        val resId = OperatorResolver.getOperatorResourceId("Rostelecom")
        assertEquals(R.string.operator_rostelecom, resId)
    }
}
