package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.android.testing.SingletonResetRule
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class OperatorResolverTest {
    @get:Rule
    val singletonResetRule = SingletonResetRule()

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
        val result = OperatorResolver.resolve(context, "", "21407", 0)
        assertEquals(context.getString(R.string.operator_movistar), result)
    }

    @Test
    fun `all tokenToResKey values exist in operatorResourceMap`() {
        val missingKeys =
            OperatorResolver.tokenToResKey.values
                .filter { it !in OperatorResolver.operatorResourceMap }

        assertTrue(
            "tokenToResKey references missing operatorResourceMap keys: $missingKeys",
            missingKeys.isEmpty(),
        )
    }

    @Test
    fun `isValidSpecificName rejects null string case-insensitively`() {
        // "NULL", "Null", etc. should trigger MCC/MNC resolution
        val result = OperatorResolver.resolve(context, "NULL", "25001", 0)
        assertEquals(context.getString(R.string.operator_mts), result)
    }

    @Test
    fun testResolveByCandidates_MTS_from_raw() {
        // MTS is 25001. Raw candidate on API 28 would be 2501.
        val candidates = listOf("2501", "25001", "250001")
        val result = OperatorResolver.resolveByCandidates(context, "", candidates, 0)
        assertEquals(context.getString(R.string.operator_mts), result)
    }

    @Test
    fun testResolveByCandidates_Unknown() {
        val candidates = listOf("9991", "99901", "999001")
        val result = OperatorResolver.resolveByCandidates(context, "", candidates, 0)
        // Should fallback to second candidate (99901) and format it
        assertEquals(context.getString(R.string.operator_with_mcc, "MCC:999 MNC:01"), result)
    }

    @Test
    fun testResolveWithMatchedCode_Success() {
        val candidates = listOf("2501", "25001", "250001")
        val resolution = OperatorResolver.resolveWithMatchedCode(context, "", candidates, 0)
        assertEquals(context.getString(R.string.operator_mts), resolution.name)
        assertEquals("25001", resolution.matchedCode)
    }
}
