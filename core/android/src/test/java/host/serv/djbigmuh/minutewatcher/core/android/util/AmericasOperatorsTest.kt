package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AmericasOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Americas PLMN database mappings`() {
        assertEquals("operator_att", resolvePlmn("310410"))
        assertEquals("operator_t_mobile", resolvePlmn("310260"))
        assertEquals("operator_verizon", resolvePlmn("311480"))
        assertEquals("operator_att", resolvePlmn("310150"))

        assertEquals("operator_telcel", resolvePlmn("334020"))
        assertEquals("operator_tim", resolvePlmn("72402"))
        assertEquals("operator_vivo", resolvePlmn("72406"))
        assertEquals("operator_claro", resolvePlmn("72405"))
        assertEquals("operator_algar", resolvePlmn("72432"))

        assertEquals("Claro", OperatorResolver.resolve(context, "", listOf("708001"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("722010"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("722070"), 0).displayName)
        assertEquals("Claro", OperatorResolver.resolve(context, "", listOf("722310"), 0).displayName)
        assertEquals("Claro", OperatorResolver.resolve(context, "", listOf("722320"), 0).displayName)
        assertEquals("Claro", OperatorResolver.resolve(context, "", listOf("722330"), 0).displayName)
        assertEquals("TIM", OperatorResolver.resolve(context, "", listOf("72403"), 0).displayName)
        assertEquals("TIM", OperatorResolver.resolve(context, "", listOf("72404"), 0).displayName)
        assertEquals("Vivo", OperatorResolver.resolve(context, "", listOf("72410"), 0).displayName)
        assertEquals("Vivo", OperatorResolver.resolve(context, "", listOf("72411"), 0).displayName)
        assertEquals("Vivo", OperatorResolver.resolve(context, "", listOf("72423"), 0).displayName)
        assertEquals("Algar", OperatorResolver.resolve(context, "", listOf("72433"), 0).displayName)
        assertEquals("Algar", OperatorResolver.resolve(context, "", listOf("72434"), 0).displayName)
        assertEquals("Claro", OperatorResolver.resolve(context, "", listOf("72438"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("732001"), 0).displayName)

        assertEquals("Rogers", OperatorResolver.resolve(context, "", listOf("302320"), 0).displayName)
        assertEquals("Rogers", OperatorResolver.resolve(context, "", listOf("302720"), 0).displayName)
        assertEquals("operator_rogers", OperatorResolver.getOperatorBrandKey("Rogers"))

        assertEquals("Bell", OperatorResolver.resolve(context, "", listOf("302610"), 0).displayName)
        assertEquals("Bell", OperatorResolver.resolve(context, "", listOf("302690"), 0).displayName)
        assertEquals("operator_bell", OperatorResolver.getOperatorBrandKey("Bell"))

        assertEquals("TELUS", OperatorResolver.resolve(context, "", listOf("302220"), 0).displayName)
        assertEquals("operator_telus", OperatorResolver.getOperatorBrandKey("TELUS"))

        assertEquals("Tigo", OperatorResolver.resolve(context, "", listOf("732103"), 0).displayName)
        assertEquals("operator_tigo", OperatorResolver.getOperatorBrandKey("Tigo"))

        assertEquals("Entel", OperatorResolver.resolve(context, "", listOf("73001"), 0).displayName)
        assertEquals("operator_entel", OperatorResolver.getOperatorBrandKey("Entel"))

        assertEquals("Personal", OperatorResolver.resolve(context, "", listOf("722340"), 0).displayName)
        assertEquals("operator_personal", OperatorResolver.getOperatorBrandKey("Personal"))
    }

    @Test
    fun `test absent Americas invented PLMNs fallback to generic format`() {
        assertEquals("Operator (MCC:311 MNC:001)", resolveFallback("311001"))
        assertEquals("Operator (MCC:312 MNC:060)", resolveFallback("312060"))
        assertEquals("Operator (MCC:316 MNC:041)", resolveFallback("316041"))
    }

    @Test
    fun `test Americas brand tokens resolve to resource strings`() {
        assertEquals(R.string.operator_telcel, OperatorResolver.getOperatorResourceId("telcel") as Int)
        assertEquals(R.string.operator_vivo, OperatorResolver.getOperatorResourceId("vivo") as Int)
        assertEquals(R.string.operator_vivo, OperatorResolver.getOperatorResourceId("telefonica vivo") as Int)
        assertEquals(R.string.operator_vivo, OperatorResolver.getOperatorResourceId("vivo brasil") as Int)
        assertEquals(R.string.operator_algar, OperatorResolver.getOperatorResourceId("algar") as Int)
        assertEquals(R.string.operator_algar, OperatorResolver.getOperatorResourceId("algar telecom") as Int)
    }

    @Test
    fun `test Americas operator icon resolution`() {
        assertEquals(R.drawable.ic_operator_telcel, SimUtils.getOperatorIconResource("telcel"))
        assertEquals(R.drawable.ic_operator_vivo, SimUtils.getOperatorIconResource("vivo"))
        assertEquals(R.drawable.ic_operator_algar, SimUtils.getOperatorIconResource("algar"))
        assertEquals(R.drawable.ic_operator_rogers, SimUtils.getOperatorIconResource("rogers"))
        assertEquals(R.drawable.ic_operator_bell, SimUtils.getOperatorIconResource("bell"))
    }

    private fun resolvePlmn(plmn: String): String? {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.brandKey
    }

    private fun resolveFallback(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
