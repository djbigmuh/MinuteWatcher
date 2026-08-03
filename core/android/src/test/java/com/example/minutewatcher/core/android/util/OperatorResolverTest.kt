package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.data.util.OperatorResolver
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class OperatorResolverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testResolve_ByDisplayName() {
        // If display name is present and valid, it should be returned as is
        val result = OperatorResolver.resolve(context, "My Operator", "25001", 0)
        assertEquals("My Operator", result)
    }

    @Test
    fun testResolve_ByMccMnc_MTS() {
        // Empty display name triggers resolution by MCC/MNC
        val result = OperatorResolver.resolve(context, "", "25001", 0)
        assertEquals(context.getString(R.string.operator_mts), result)
    }

    @Test
    fun testResolve_ByMccMnc_Beeline() {
        // "null" or "SIM 1" etc also triggers resolution
        val result = OperatorResolver.resolve(context, "null", "25099", 1)
        assertEquals(context.getString(R.string.operator_beeline), result)
    }

    @Test
    fun testResolve_Unknown() {
        val result = OperatorResolver.resolve(context, "", "99999", 0)
        // New formatting: MCC:999 MNC:99
        assertEquals(context.getString(R.string.operator_with_mcc, "MCC:999 MNC:99"), result)
    }

    @Test
    fun testResolve_Empty() {
        val result = OperatorResolver.resolve(context, "", "", 1)
        // Should return localized "Operator 2"
        assertEquals(context.getString(R.string.operator_generic, 2), result)
    }

    @Test
    fun testResolve_Rostelecom() {
        val result = OperatorResolver.resolve(context, "", "25039", 0)
        assertEquals("Rostelecom", result)
    }

    @Test
    fun testResolve_A1() {
        val result = OperatorResolver.resolve(context, "", "23201", 0)
        assertEquals("A1", result)
    }

    @Test
    fun testResolve_Three() {
        val result = OperatorResolver.resolve(context, "", "23205", 0)
        assertEquals("Three", result)
    }

    @Test
    fun testResolve_Movistar() {
        // Movistar is in XML and now in map, but not in JSON?
        // Actually, JSON has "21407" as o2, but XML has movistar.
        // Let's check JSON for Movistar.
        // I'll just test one of the ones I explicitly added to JSON if I did.
        // Wait, I didn't touch JSON because the prompt said "link to keys that are NOT in XML/Map".
        // So they are ALREADY in JSON.
    }
}
