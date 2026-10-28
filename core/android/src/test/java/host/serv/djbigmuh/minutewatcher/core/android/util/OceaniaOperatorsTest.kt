package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OceaniaOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Oceania PLMN database mappings`() {
        assertEquals("Telstra", resolveDisplayName("50501"))
        assertEquals("Optus", resolveDisplayName("50502"))
        assertEquals("Vodafone", resolveDisplayName("50503"))
        assertEquals("One NZ", resolveDisplayName("53001"))
        assertEquals("Spark", resolveDisplayName("53005"))
        assertEquals("2degrees", resolveDisplayName("53024"))
        assertEquals("B-Mobile", resolveDisplayName("53701"))
        assertEquals("Digicel", resolveDisplayName("53703"))
        assertEquals("Digicel", resolveDisplayName("54105"))

        assertEquals("operator_smile", OperatorResolver.resolve(context, "", listOf("54101"), 0).brandKey)
        assertEquals("operator_bmobile_sb", OperatorResolver.resolve(context, "", listOf("54002"), 0).brandKey)
        assertEquals("operator_ucall", OperatorResolver.resolve(context, "", listOf("53901"), 0).brandKey)
        assertEquals("operator_digicel", OperatorResolver.resolve(context, "", listOf("53988"), 0).brandKey)
        assertEquals("operator_digicel", OperatorResolver.resolve(context, "", listOf("54900"), 0).brandKey)
        assertEquals("operator_digicel", OperatorResolver.resolve(context, "", listOf("54901"), 0).brandKey)
        assertEquals("operator_bluesky", OperatorResolver.resolve(context, "", listOf("54411"), 0).brandKey)
        assertEquals("operator_mobilis_nc", OperatorResolver.resolve(context, "", listOf("54601"), 0).brandKey)
        assertEquals("operator_vini", OperatorResolver.resolve(context, "", listOf("54720"), 0).brandKey)
        assertEquals("operator_vodafone", OperatorResolver.resolve(context, "", listOf("54801"), 0).brandKey)
        assertEquals("operator_frigate", OperatorResolver.resolve(context, "", listOf("54509"), 0).brandKey)
        assertEquals("operator_fsm_telecom", OperatorResolver.resolve(context, "", listOf("55001"), 0).brandKey)
        assertEquals("operator_pncc", OperatorResolver.resolve(context, "", listOf("55201"), 0).brandKey)
        assertEquals("operator_ttc", OperatorResolver.resolve(context, "", listOf("55301"), 0).brandKey)
        assertEquals("Telecom Niue", resolveDisplayName("55501"))
        assertEquals("operator_telecom_niue", OperatorResolver.resolve(context, "", listOf("55501"), 0).brandKey)
        assertEquals("operator_digicel", OperatorResolver.resolve(context, "", listOf("53602"), 0).brandKey)
        assertEquals("operator_manuia", OperatorResolver.resolve(context, "", listOf("54301"), 0).brandKey)
        assertEquals("operator_teletok", OperatorResolver.resolve(context, "", listOf("55401"), 0).brandKey)
        assertEquals("operator_frigate", OperatorResolver.resolve(context, "", listOf("54501"), 0).brandKey)
        assertEquals("operator_defence_au", OperatorResolver.resolve(context, "", listOf("50504"), 0).brandKey)
        assertEquals("operator_nta", OperatorResolver.resolve(context, "", listOf("55101"), 0).brandKey)
    }

    @Test
    fun `test One NZ brand token resolves to resource string and icon`() {
        assertEquals(R.string.operator_onenz, OperatorResolver.getOperatorResourceId("one nz") as Int)
        assertEquals(R.string.operator_onenz, OperatorResolver.getOperatorResourceId("one new zealand") as Int)
        assertEquals(R.drawable.ic_operator_onenz, SimUtils.getOperatorIconResource("one nz"))
        assertEquals(
            R.string.operator_manuia,
            OperatorResolver.getOperatorResourceId("manuia") as Int,
        )
        assertEquals(
            R.string.operator_teletok,
            OperatorResolver.getOperatorResourceId("teletok") as Int,
        )
        assertEquals(
            R.string.operator_pmci,
            OperatorResolver.getOperatorResourceId("pmci") as Int,
        )
        assertEquals(
            R.string.operator_norfolk_telecom,
            OperatorResolver.getOperatorResourceId("norfolk telecom") as Int,
        )
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
