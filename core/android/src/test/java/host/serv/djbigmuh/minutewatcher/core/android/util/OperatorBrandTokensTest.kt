package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OperatorBrandTokensTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        OperatorResolver.reset()
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `token map iteration order and helper function validation`() {
        val keys = OperatorBrandTokens.tokenToResKey.keys.toList()
        assertTrue("tokenToResKey must not be empty", keys.isNotEmpty())

        val firstExpected = listOf("bell", "bell mobility", "rogers", "rogers wireless", "meo")
        assertEquals(firstExpected, keys.take(5))

        val lastExpected =
            listOf(
                "sweden 3g",
                "moov mauritel",
                "liberty",
                "liberty latin america",
                "nema",
            )
        assertEquals(lastExpected, keys.takeLast(5))

        try {
            OperatorBrandTokens.mergeTokenMaps(
                mapOf("duplicate_key" to "operator_a"),
                mapOf("duplicate_key" to "operator_b"),
            )
            fail("Expected IllegalStateException when merging duplicate keys")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message must contain duplicate key name",
                e.message?.contains("duplicate_key") == true,
            )
            assertTrue(
                "Exception message must contain both values",
                e.message?.contains("operator_a") == true && e.message?.contains("operator_b") == true,
            )
        }
    }

    @Test
    fun `tokenMapOf throws on duplicate key`() {
        try {
            OperatorBrandTokens.tokenMapOf(
                "dup" to "val1",
                "dup" to "val2",
            )
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("dup") == true)
            assertTrue(e.message?.contains("val1") == true && e.message?.contains("val2") == true)
        }
    }

    @Test
    fun `tokenToResKey validation`() {
        val tokenMap = OperatorBrandTokens.tokenToResKey
        assertTrue(tokenMap.isNotEmpty())
        for ((_, brandKey) in tokenMap) {
            assertTrue(OperatorBrandDatabase.operatorResourceMap.containsKey(brandKey))
        }
    }

    @Test
    fun `operator brand resolution regression tests for restructured tokens`() {
        assertEquals("operator_masmovil", OperatorResolver.getOperatorBrandKey("MASMovil"))
        assertEquals("operator_masmovil", OperatorResolver.getOperatorBrandKey("MásMóvil"))
        assertEquals("operator_e_and", OperatorResolver.getOperatorBrandKey("e&"))
        assertNull(OperatorResolver.getOperatorBrandKey("bmobile"))
        assertNull(OperatorResolver.getOperatorBrandKey("movil"))

        val bmobileTtNoName = OperatorResolver.resolve(context, "", listOf("37412"), 0)
        assertEquals("operator_bmobile_tt", bmobileTtNoName.brandKey)

        val bmobileTtNamed = OperatorResolver.resolve(context, "bmobile", listOf("37412"), 0)
        assertEquals("operator_bmobile_tt", bmobileTtNamed.brandKey)
        assertEquals("bmobile", bmobileTtNamed.displayName)

        val bmobileBrunei = OperatorResolver.resolve(context, "bmobile", listOf("52802"), 0)
        assertEquals("operator_pcsb", bmobileBrunei.brandKey)

        val flowPanama = OperatorResolver.resolve(context, "Flow", listOf("71401"), 0)
        assertEquals("operator_flow", flowPanama.brandKey)

        val flowPanamaNoName = OperatorResolver.resolve(context, "", listOf("71401"), 0)
        assertEquals("Flow", flowPanamaNoName.displayName)

        val iceKolbi = OperatorResolver.resolve(context, "ICE", listOf("71201"), 0)
        assertEquals("operator_kolbi", iceKolbi.brandKey)

        val iceNorway = OperatorResolver.resolve(context, "ice", listOf("24206"), 0)
        assertEquals("operator_ice", iceNorway.brandKey)

        val vivaBolivia = OperatorResolver.resolve(context, "Viva", listOf("73601"), 0)
        assertEquals("operator_viva_bo", vivaBolivia.brandKey)

        val vivaArmenia = OperatorResolver.resolve(context, "Viva", listOf("28305"), 0)
        assertEquals("operator_viva", vivaArmenia.brandKey)

        val oneAlbania = OperatorResolver.resolve(context, "One", listOf("27601"), 0)
        assertEquals("operator_one", oneAlbania.brandKey)

        val etisalatAf = OperatorResolver.resolve(context, "Etisalat", listOf("41250"), 0)
        assertEquals("operator_etisalat", etisalatAf.brandKey)

        val eAndUae = OperatorResolver.resolve(context, "e&", listOf("42402"), 0)
        assertEquals("operator_e_and", eAndUae.brandKey)

        val alfaLebanon = OperatorResolver.resolve(context, "Alfa", listOf("41501"), 0)
        assertEquals("operator_alfa_lb", alfaLebanon.brandKey)

        val threeHk = OperatorResolver.resolve(context, "Three", listOf("45403"), 0)
        assertEquals("operator_three_hk", threeHk.brandKey)

        val threeUk = OperatorResolver.resolve(context, "3", listOf("23420"), 0)
        assertEquals("operator_three", threeUk.brandKey)
    }
}
