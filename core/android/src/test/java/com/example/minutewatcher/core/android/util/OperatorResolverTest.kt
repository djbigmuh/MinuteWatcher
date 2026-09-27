package com.example.minutewatcher.core.android.util

import android.content.Context
import android.content.res.AssetManager
import com.example.minutewatcher.core.android.testing.SingletonResetRule
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val result = OperatorResolver.resolve(context, "My Operator", emptyList(), 0)
        assertEquals("My Operator", result)
    }

    @Test
    fun testResolve_ByMccMnc_MTS() {
        val result = OperatorResolver.resolve(context, "", listOf("25001"), 0)
        assertEquals("MTS", result)
    }

    @Test
    fun testResolve_ByMccMnc_Beeline() {
        val result = OperatorResolver.resolve(context, "", listOf("25099"), 1)
        assertEquals("Beeline", result)
    }

    @Test
    fun `resolve treats string literal null as specific display name`() {
        val result = OperatorResolver.resolve(context, "null", listOf("25099"), 1)
        assertEquals("null", result)
    }

    @Test
    fun testResolve_Unknown() {
        val result = OperatorResolver.resolve(context, "", listOf("99999"), 0)
        assertEquals("Operator with mcc MCC:999 MNC:99", result)
    }

    @Test
    fun testResolve_Empty() {
        val result = OperatorResolver.resolve(context, "", emptyList(), 1)
        assertEquals("Operator 2", result)
    }

    @Test
    fun testResolve_Rostelecom() {
        val result = OperatorResolver.resolve(context, "", listOf("25039"), 0)
        assertEquals("Rostelecom", result)
    }

    @Test
    fun testResolve_A1() {
        val result = OperatorResolver.resolve(context, "", listOf("23201"), 0)
        assertEquals("A1", result)
    }

    @Test
    fun testResolve_Three() {
        val result = OperatorResolver.resolve(context, "", listOf("23205"), 0)
        assertEquals("Three", result)
    }

    @Test
    fun testResolve_Movistar() {
        val result = OperatorResolver.resolve(context, "", listOf("21407"), 0)
        assertEquals("Movistar", result)
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
    fun `isValidSpecificName rejects generic sim pattern and empty string`() {
        val genericResult = OperatorResolver.resolve(context, "SIM 1", listOf("25001"), 0)
        assertEquals("MTS", genericResult)

        val emptyResult = OperatorResolver.resolve(context, "", listOf("25001"), 0)
        assertEquals("MTS", emptyResult)
    }

    @Test
    fun testResolve_Success() {
        val candidates = listOf("2501", "25001", "250001")
        val result = OperatorResolver.resolve(context, "", candidates, 0)
        assertEquals("MTS", result)
    }

    @Test
    fun testResolve_FallbackToGeneric() {
        val candidates = listOf("99901", "9991", "999001")
        val result = OperatorResolver.resolve(context, "", candidates, 0)
        assertEquals("Operator with mcc MCC:999 MNC:01", result)
    }

    @Test
    fun `loadDbInternal loads all keys from the database`() {
        val candidates = listOf("25001", "25099", "21407", "25039", "23201", "23205")
        for (code in candidates) {
            val result = OperatorResolver.resolve(context, "", listOf(code), 0)
            assertTrue("Should resolve code $code", !result.startsWith("Operator "))
        }
    }

    @Test
    fun `getOperatorResourceId returns operator_telekom for Telekom`() {
        assertEquals(R.string.operator_telekom, OperatorResolver.getOperatorResourceId("Telekom"))
    }

    @Test
    fun `getOperatorResourceId returns operator_t_mobile for T-Mobile`() {
        assertEquals(R.string.operator_t_mobile, OperatorResolver.getOperatorResourceId("T-Mobile"))
    }

    @Test
    fun `getOperatorResourceId returns operator_china_mobile for China Mobile variations`() {
        assertEquals(R.string.operator_china_mobile, OperatorResolver.getOperatorResourceId("China Mobile"))
        assertEquals(R.string.operator_china_mobile, OperatorResolver.getOperatorResourceId("CHINA MOBILE"))
        assertEquals(
            R.string.operator_china_mobile,
            OperatorResolver.getOperatorResourceId("China Mobile Communications"),
        )
    }

    @Test
    fun `getOperatorResourceId returns operator_ntt_docomo for Docomo variations`() {
        assertEquals(R.string.operator_ntt_docomo, OperatorResolver.getOperatorResourceId("NTT Docomo"))
        assertEquals(R.string.operator_ntt_docomo, OperatorResolver.getOperatorResourceId("Docomo"))
    }

    @Test
    fun `getOperatorResourceId returns operator_vodafone for Vodafone`() {
        assertEquals(R.string.operator_vodafone, OperatorResolver.getOperatorResourceId("Vodafone"))
    }

    @Test
    fun `getOperatorResourceId does not match Timor with tim`() {
        assertNull(OperatorResolver.getOperatorResourceId("Timor"))
    }
}
