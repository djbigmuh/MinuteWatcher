package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import android.content.res.AssetManager
import host.serv.djbigmuh.minutewatcher.core.android.testing.SingletonResetRule
import host.serv.djbigmuh.minutewatcher.core.resources.R
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
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
        OperatorResolver.reset()
        doReturn(context).whenever(context).createConfigurationContext(any())
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
    fun `resolve by display name returns correct brandKey`() {
        val result = OperatorResolver.resolve(context, "MTS", emptyList(), 0)
        assertEquals("MTS", result.displayName)
        assertEquals("operator_mts", result.brandKey)

        val customResult = OperatorResolver.resolve(context, "My Operator", emptyList(), 0)
        assertEquals("My Operator", customResult.displayName)
        assertNull(customResult.brandKey)
    }

    @Test
    fun `resolve by MccMnc MTS`() {
        val result = OperatorResolver.resolve(context, "", listOf("25001"), 0)
        assertEquals("MTS", result.displayName)
        assertEquals("operator_mts", result.brandKey)
    }

    @Test
    fun `resolve by MccMnc Beeline`() {
        val result = OperatorResolver.resolve(context, "", listOf("25099"), 1)
        assertEquals("Beeline", result.displayName)
        assertEquals("operator_beeline", result.brandKey)
    }

    @Test
    fun `resolve treats string literal null as non-specific and resolves via database`() {
        val resultNull = OperatorResolver.resolve(context, "null", listOf("25099"), 1)
        assertEquals("Beeline", resultNull.displayName)

        val resultUpper = OperatorResolver.resolve(context, "NULL", listOf("25099"), 1)
        assertEquals("Beeline", resultUpper.displayName)

        val resultMixed = OperatorResolver.resolve(context, "Null", listOf("25099"), 1)
        assertEquals("Beeline", resultMixed.displayName)
    }

    @Test
    fun `resolve treats mcc mnc format string as non-specific and resolves via database`() {
        val result = OperatorResolver.resolve(context, "25001", listOf("25001"), 0)
        assertEquals("MTS", result.displayName)
    }

    @Test
    fun `resolve treats unknown mcc mnc format string as non-specific and falls back`() {
        val result = OperatorResolver.resolve(context, "99999", listOf("99999"), 0)
        assertEquals("Operator with mcc MCC:999 MNC:99", result.displayName)
        assertNull(result.brandKey)
    }

    @Test
    fun `resolve retains short digit string as specific name`() {
        val result = OperatorResolver.resolve(context, "3", listOf("25001"), 0)
        assertEquals("3", result.displayName)
    }

    @Test
    fun `resolve retains non digit string as specific name`() {
        val result1 = OperatorResolver.resolve(context, "4G", listOf("25001"), 0)
        assertEquals("4G", result1.displayName)

        val result2 = OperatorResolver.resolve(context, "mt3", listOf("25001"), 0)
        assertEquals("mt3", result2.displayName)
    }

    @Test
    fun `resolve trims specific operator name`() {
        val result = OperatorResolver.resolve(context, "  My Operator  ", listOf("25001"), 0)
        assertEquals("My Operator", result.displayName)
    }

    @Test
    fun `resolve unknown`() {
        val result = OperatorResolver.resolve(context, "", listOf("99999"), 0)
        assertEquals("Operator with mcc MCC:999 MNC:99", result.displayName)
    }

    @Test
    fun `resolve empty`() {
        val result = OperatorResolver.resolve(context, "", emptyList(), 1)
        assertEquals("Operator 2", result.displayName)
    }

    @Test
    fun `resolve Rostelecom`() {
        val result = OperatorResolver.resolve(context, "", listOf("25039"), 0)
        assertEquals("Rostelecom", result.displayName)
        assertEquals("operator_rostelecom", result.brandKey)
    }

    @Test
    fun `resolve A1`() {
        val result = OperatorResolver.resolve(context, "", listOf("23201"), 0)
        assertEquals("A1", result.displayName)
    }

    @Test
    fun `resolve Three`() {
        val result = OperatorResolver.resolve(context, "", listOf("23205"), 0)
        assertEquals("Three", result.displayName)
    }

    @Test
    fun `resolve Movistar`() {
        val result = OperatorResolver.resolve(context, "", listOf("21407"), 0)
        assertEquals("Movistar", result.displayName)
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
        assertEquals("MTS", genericResult.displayName)

        val emptyResult = OperatorResolver.resolve(context, "", listOf("25001"), 0)
        assertEquals("MTS", emptyResult.displayName)
    }

    @Test
    fun `resolve success`() {
        val candidates = listOf("2501", "25001", "250001")
        val result = OperatorResolver.resolve(context, "", candidates, 0)
        assertEquals("MTS", result.displayName)
    }

    @Test
    fun `resolve fallback to generic`() {
        val candidates = listOf("99901", "9991", "999001")
        val result = OperatorResolver.resolve(context, "", candidates, 0)
        assertEquals("Operator with mcc MCC:999 MNC:01", result.displayName)
    }

    @Test
    fun `loadDbInternal loads all keys from the database`() {
        val candidates = listOf("25001", "25099", "21407", "25039", "23201", "23205")
        for (code in candidates) {
            val result = OperatorResolver.resolve(context, "", listOf(code), 0)
            assertTrue("Should resolve code $code", !result.displayName.startsWith("Operator "))
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

    @Test
    fun `getOperatorResourceId returns correct resource for cyrillic brand names and prevents false positives`() {
        assertEquals(R.string.operator_mts, OperatorResolver.getOperatorResourceId("МТС"))
        assertEquals(R.string.operator_mgts, OperatorResolver.getOperatorResourceId("МГТС"))
    }

    @Test
    fun `first load fails then second succeeds`() {
        var openCount = 0
        whenever(assets.open(any())).thenAnswer {
            openCount++
            if (openCount == 1) {
                throw IOException("Simulated asset error")
            } else {
                ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
            }
        }

        val result1 = OperatorResolver.resolve(context, "", listOf("25001"), 0)
        assertEquals("Operator with mcc MCC:250 MNC:01", result1.displayName)

        val result2 = OperatorResolver.resolve(context, "", listOf("25001"), 0)
        assertEquals("MTS", result2.displayName)
    }

    @Test
    fun `asset always unavailable stops opening after max attempts`() {
        var openCount = 0
        whenever(assets.open(any())).thenAnswer {
            openCount++
            throw IOException("Always down")
        }

        repeat(3) {
            OperatorResolver.resolve(context, "", listOf("25001"), 0)
        }
        val countAfter3 = openCount
        assertEquals(3, countAfter3)

        repeat(2) {
            OperatorResolver.resolve(context, "", listOf("25001"), 0)
        }
        assertEquals(3, openCount)
    }

    @Test
    fun `successful load is cached and asset opened once`() {
        var openCount = 0
        whenever(assets.open(any())).thenAnswer {
            openCount++
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        }

        assertEquals("MTS", OperatorResolver.resolve(context, "", listOf("25001"), 0).displayName)
        assertEquals("Beeline", OperatorResolver.resolve(context, "", listOf("25099"), 0).displayName)
        assertEquals(1, openCount)
    }

    @Test
    fun `resetForTest restores load capability`() {
        var openCount = 0
        whenever(assets.open(any())).thenAnswer {
            openCount++
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        }

        assertEquals("MTS", OperatorResolver.resolve(context, "", listOf("25001"), 0).displayName)
        assertEquals(1, openCount)

        OperatorResolver.reset()
        assertEquals("MTS", OperatorResolver.resolve(context, "", listOf("25001"), 0).displayName)
        assertEquals(2, openCount)
    }

    @Test
    fun `concurrent load from multiple threads is thread safe`() =
        runBlocking {
            var openCount = 0
            whenever(assets.open(any())).thenAnswer {
                openCount++
                Thread.sleep(5)
                ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
            }

            val threads =
                List(10) {
                    Thread {
                        OperatorResolver.resolve(context, "", listOf("25001"), 0)
                    }
                }
            threads.forEach { it.start() }
            withTimeout(5.seconds) {
                threads.forEach {
                    while (it.isAlive) {
                        kotlinx.coroutines.delay(10.milliseconds)
                    }
                }
            }
            assertTrue("Open count should be at most 3, was $openCount", openCount <= 3)
        }

    @Test
    fun `getOperatorResourceId handles diacritics and specificity correctly`() {
        assertEquals(R.string.operator_turk_telekom, OperatorResolver.getOperatorResourceId("Türk Telekom"))
        assertEquals(R.string.operator_turk_telekom, OperatorResolver.getOperatorResourceId("Turk Telekom"))
        assertEquals(R.string.operator_turk_telekom, OperatorResolver.getOperatorResourceId("TÜRK TELEKOM"))
        assertEquals(R.string.operator_telekom, OperatorResolver.getOperatorResourceId("Telekom"))
        assertEquals(R.string.operator_telekom, OperatorResolver.getOperatorResourceId("Deutsche Telekom"))
        assertEquals(R.string.operator_turkcell, OperatorResolver.getOperatorResourceId("Turkcell"))
        assertEquals(R.string.operator_yota, OperatorResolver.getOperatorResourceId("Йота"))
        assertEquals(R.string.operator_yota, OperatorResolver.getOperatorResourceId("yota"))
        assertEquals(R.string.operator_yota, OperatorResolver.getOperatorResourceId("YOTA"))
        assertEquals(R.string.operator_tele2, OperatorResolver.getOperatorResourceId("Т2"))
        assertNull(OperatorResolver.getOperatorResourceId("Timor Telecom"))
    }

    @Test
    fun `test EE brand resolution by name`() {
        assertEquals(R.string.operator_ee, OperatorResolver.getOperatorResourceId("EE"))
        assertEquals(R.string.operator_ee, OperatorResolver.getOperatorResourceId("ee"))
    }

    @Test
    fun `test PLMN resolution for corrected PLMNs`() {
        doReturn("O2").whenever(context).getString(eq(R.string.operator_o2))
        doReturn("EE").whenever(context).getString(eq(R.string.operator_ee))

        val jsonReal =
            """
            {
                "26203": "operator_o2",
                "23433": "operator_ee"
            }
            """.trimIndent()

        whenever(assets.open(any())).thenAnswer {
            ByteArrayInputStream(jsonReal.toByteArray(Charsets.UTF_8))
        }

        OperatorResolver.reset()
        assertEquals("O2", OperatorResolver.resolve(context, "", listOf("26203"), 0).displayName)
        OperatorResolver.reset()
        assertEquals("EE", OperatorResolver.resolve(context, "", listOf("23433"), 0).displayName)
    }

    @Test
    fun `test removed PLMN 25003 does not resolve to MGTS`() {
        val jsonNo25003 =
            """
            {
                "25001": "operator_mts"
            }
            """.trimIndent()

        whenever(assets.open(any())).thenAnswer {
            ByteArrayInputStream(jsonNo25003.toByteArray(Charsets.UTF_8))
        }

        OperatorResolver.reset()
        val result = OperatorResolver.resolve(context, "", listOf("25003"), 0)
        org.junit.Assert.assertNotEquals("MGTS", result.displayName)
    }

    @Test
    fun `resolve with specific name and matching plmn in db returns brandKey`() {
        val result = OperatorResolver.resolve(context, "3", listOf("23205"), 0)
        assertEquals("3", result.displayName)
        assertEquals("operator_three", result.brandKey)
    }

    @Test
    fun `resolve with specific name and absent plmn in db returns null brandKey`() {
        val result = OperatorResolver.resolve(context, "UnknownCarrier XYZ", listOf("99999"), 0)
        assertEquals("UnknownCarrier XYZ", result.displayName)
        assertNull(result.brandKey)
    }

    @Test
    fun `resolve name token priority over db plmn`() {
        val result = OperatorResolver.resolve(context, "MTS", listOf("21407"), 0)
        assertEquals("MTS", result.displayName)
        assertEquals("operator_mts", result.brandKey)
    }

    @Test
    fun `resolve empty name and invalid candidates returns generic name without MCC`() {
        val result = OperatorResolver.resolve(context, "", listOf("00000"), 0)
        org.junit.Assert.assertFalse(result.displayName.contains("MCC:"))
    }

    @Test
    fun `resolve empty name skips invalid candidate and uses valid second candidate`() {
        val result = OperatorResolver.resolve(context, "", listOf("00000", "99999"), 0)
        assertEquals("Operator with mcc MCC:999 MNC:99", result.displayName)
    }

    @Test
    fun `simutils get operator icon resource for operator three`() {
        assertEquals(R.drawable.ic_operator_three, SimUtils.getOperatorIconResource("Three"))
    }
}
