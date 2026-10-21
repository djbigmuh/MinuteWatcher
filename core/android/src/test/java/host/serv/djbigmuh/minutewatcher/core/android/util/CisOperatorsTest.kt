package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CisOperatorsTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        OperatorResolver.reset()
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `mcc mnc database contains cis assignments`() {
        val json =
            context.assets.open("mcc_mnc_db.json").use { input ->
                JSONObject(String(input.readBytes(), Charsets.UTF_8))
            }
        assertEquals("operator_mts", json.getString("25001"))
        assertEquals("operator_megafon", json.getString("25002"))
        assertEquals("operator_beeline", json.getString("25028"))
        assertEquals("operator_rostelecom", json.getString("25039"))
        assertEquals("operator_mgts", json.getString("25050"))
        assertEquals("operator_tattelecom", json.getString("25027"))
        assertEquals("operator_yota", json.getString("25011"))
        assertEquals("operator_kyivstar", json.getString("25502"))
        assertEquals("operator_kyivstar", json.getString("25503"))
        assertEquals("operator_lifecell", json.getString("25506"))
        assertEquals("operator_a1", json.getString("25701"))
        assertEquals("operator_mts", json.getString("25702"))
        assertEquals("operator_life_by", json.getString("25704"))
        assertEquals("operator_orange", json.getString("25901"))
        assertEquals("operator_moldcell", json.getString("25902"))
        assertEquals("operator_beeline", json.getString("28204"))
        assertEquals("operator_beeline", json.getString("43701"))

        assertEquals("Azercell", OperatorResolver.resolve(context, "", listOf("40001"), 0).displayName)
        assertEquals("operator_azercell", OperatorResolver.getOperatorBrandKey("Azercell"))

        assertEquals("Kcell", OperatorResolver.resolve(context, "", listOf("40102"), 0).displayName)
        assertEquals("operator_kcell", OperatorResolver.getOperatorBrandKey("Kcell"))
    }

    @Test
    fun `cis brand tokens resolve to brand string resources`() {
        assertEquals(R.string.operator_mts, OperatorResolver.getOperatorResourceId("MTS"))
        assertEquals(R.string.operator_megafon, OperatorResolver.getOperatorResourceId("MegaFon"))
        assertEquals(R.string.operator_beeline, OperatorResolver.getOperatorResourceId("Beeline"))
        assertEquals(R.string.operator_tele2, OperatorResolver.getOperatorResourceId("Tele2"))
        assertEquals(R.string.operator_rostelecom, OperatorResolver.getOperatorResourceId("Rostelecom"))
        assertEquals(R.string.operator_tattelecom, OperatorResolver.getOperatorResourceId("Tattelecom"))
        assertEquals(R.string.operator_yota, OperatorResolver.getOperatorResourceId("Yota"))
        assertEquals(R.string.operator_kyivstar, OperatorResolver.getOperatorResourceId("Kyivstar"))
        assertEquals(R.string.operator_lifecell, OperatorResolver.getOperatorResourceId("lifecell"))
        assertEquals(R.string.operator_moldcell, OperatorResolver.getOperatorResourceId("Moldcell"))
        assertEquals(R.string.operator_life_by, OperatorResolver.getOperatorResourceId("life:)"))
        assertEquals(R.string.operator_a1, OperatorResolver.getOperatorResourceId("A1"))
        assertEquals(R.string.operator_azercell, OperatorResolver.getOperatorResourceId("azercell"))
        assertEquals(R.string.operator_kcell, OperatorResolver.getOperatorResourceId("kcell"))
    }

    @Test
    fun `cis brands resolve icons correctly`() {
        assertEquals(R.drawable.ic_operator_mts, SimUtils.getOperatorIconResource("MTS"))
        assertEquals(R.drawable.ic_operator_megafon, SimUtils.getOperatorIconResource("MegaFon"))
        assertEquals(R.drawable.ic_operator_beeline, SimUtils.getOperatorIconResource("Beeline"))
        assertEquals(R.drawable.ic_operator_tele2, SimUtils.getOperatorIconResource("Tele2"))
        assertEquals(R.drawable.ic_operator_kyivstar, SimUtils.getOperatorIconResource("Kyivstar"))
        assertEquals(R.drawable.ic_operator_lifecell, SimUtils.getOperatorIconResource("lifecell"))
        assertEquals(R.drawable.ic_operator_moldcell, SimUtils.getOperatorIconResource("Moldcell"))
        assertEquals(R.drawable.ic_operator_life_by, SimUtils.getOperatorIconResource("life:)"))
        assertEquals(R.drawable.ic_operator_azercell, SimUtils.getOperatorIconResource("azercell"))
        assertEquals(R.drawable.ic_operator_kcell, SimUtils.getOperatorIconResource("kcell"))
        assertEquals(R.drawable.ic_operator_bite, SimUtils.getOperatorIconResource("Bite"))
        assertEquals(R.drawable.ic_operator_bakcell, SimUtils.getOperatorIconResource("Bakcell"))
        assertEquals(R.drawable.ic_operator_nar, SimUtils.getOperatorIconResource("Nar"))
        assertEquals(R.drawable.ic_operator_geocell, SimUtils.getOperatorIconResource("Geocell"))
        assertEquals(R.drawable.ic_operator_altel, SimUtils.getOperatorIconResource("Altel"))
    }

    @Test
    fun `resolve cis operators via mcc mnc and name`() {
        val resultKyivstar = OperatorResolver.resolve(context, "", listOf("25502"), 0)
        assertEquals("Kyivstar", resultKyivstar.displayName)
        assertEquals("operator_kyivstar", resultKyivstar.brandKey)

        val resultLifecell = OperatorResolver.resolve(context, "lifecell", emptyList(), 0)
        assertEquals("lifecell", resultLifecell.displayName)
        assertEquals("operator_lifecell", resultLifecell.brandKey)
    }
}
