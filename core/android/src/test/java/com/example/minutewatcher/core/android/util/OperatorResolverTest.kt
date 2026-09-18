package com.example.minutewatcher.core.android.util

import android.content.Context
import android.content.res.AssetManager
import com.example.minutewatcher.core.android.testing.SingletonResetRule
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream

class OperatorResolverTest {
    @get:Rule
    val singletonResetRule = SingletonResetRule()

    private val context: Context = mock()
    private val assets: AssetManager = mock()

    private val json =
        """
        {
            "25001": "operator_mts",
            "25099": "operator_beeline",
            "21407": "operator_movistar",
            "25039": "operator_rostelecom",
            "23201": "operator_a1",
            "23205": "operator_three"
        }
        """.trimIndent()

    @Before
    fun setup() {
        OperatorResolver.resetForTest()
        whenever(context.assets).thenReturn(assets)

        doAnswer {
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        }.whenever(assets).open(any())

        doReturn("MTS").whenever(context).getString(eq(R.string.operator_mts))
        doReturn("Beeline").whenever(context).getString(eq(R.string.operator_beeline))
        doReturn("Movistar").whenever(context).getString(eq(R.string.operator_movistar))
        doReturn("Rostelecom").whenever(context).getString(eq(R.string.operator_rostelecom))
        doReturn("A1").whenever(context).getString(eq(R.string.operator_a1))
        doReturn("Three").whenever(context).getString(eq(R.string.operator_three))

        doAnswer {
            "Operator with mcc ${it.arguments[1]}"
        }.whenever(context).getString(eq(R.string.operator_with_mcc), any())

        doAnswer {
            "Operator ${it.arguments[1]}"
        }.whenever(context).getString(eq(R.string.operator_generic), any())
    }

    @Test
    fun testResolve_ByDisplayName() {
        val result = OperatorResolver.resolveWithMatchedCode(context, "My Operator", emptyList(), 0)
        assertEquals("My Operator", result.name)
    }

    @Test
    fun testResolve_ByMccMnc_MTS() {
        val result = OperatorResolver.resolveWithMatchedCode(context, "", listOf("25001"), 0)
        assertEquals("MTS", result.name)
        assertEquals("25001", result.matchedCode)
    }

    @Test
    fun testResolve_ByMccMnc_Beeline() {
        val result = OperatorResolver.resolveWithMatchedCode(context, "null", listOf("25099"), 1)
        assertEquals("Beeline", result.name)
        assertEquals("25099", result.matchedCode)
    }

    @Test
    fun testResolve_Unknown() {
        val result = OperatorResolver.resolveWithMatchedCode(context, "", listOf("99999"), 0)
        assertEquals("Operator with mcc MCC:999 MNC:99", result.name)
    }

    @Test
    fun testResolve_Empty() {
        val result = OperatorResolver.resolveWithMatchedCode(context, "", emptyList(), 1)
        assertEquals("Operator 2", result.name)
    }

    @Test
    fun testResolve_Rostelecom() {
        val result = OperatorResolver.resolveWithMatchedCode(context, "", listOf("25039"), 0)
        assertEquals("Rostelecom", result.name)
    }

    @Test
    fun testResolve_A1() {
        val result = OperatorResolver.resolveWithMatchedCode(context, "", listOf("23201"), 0)
        assertEquals("A1", result.name)
    }

    @Test
    fun testResolve_Three() {
        val result = OperatorResolver.resolveWithMatchedCode(context, "", listOf("23205"), 0)
        assertEquals("Three", result.name)
    }

    @Test
    fun testResolve_Movistar() {
        val result = OperatorResolver.resolveWithMatchedCode(context, "", listOf("21407"), 0)
        assertEquals("Movistar", result.name)
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
        val result = OperatorResolver.resolveWithMatchedCode(context, "NULL", listOf("25001"), 0)
        assertEquals("MTS", result.name)
    }

    @Test
    fun testResolveWithMatchedCode_Success() {
        val candidates = listOf("2501", "25001", "250001")
        val resolution = OperatorResolver.resolveWithMatchedCode(context, "", candidates, 0)
        assertEquals("MTS", resolution.name)
        assertEquals("25001", resolution.matchedCode)
    }

    @Test
    fun testResolveWithMatchedCode_FallbackToGeneric() {
        val candidates = listOf("99901", "9991", "999001")
        val result = OperatorResolver.resolveWithMatchedCode(context, "", candidates, 0)
        assertEquals("Operator with mcc MCC:999 MNC:01", result.name)
    }

    @Test
    fun `loadDbInternal loads all keys from the database`() {
        // We use a real file if it's available in assets or just verify the logic with our json mock
        // Since we're in a unit test with mocked assets, the 'json' field above is used.
        val candidates = listOf("25001", "25099", "21407", "25039", "23201", "23205")
        for (code in candidates) {
            val result = OperatorResolver.resolveWithMatchedCode(context, "", listOf(code), 0)
            assertTrue("Should match code $code", result.matchedCode == code)
        }
    }
}
