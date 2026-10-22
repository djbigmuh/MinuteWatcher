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
        assertEquals("Vidéotron", OperatorResolver.resolve(context, "", listOf("302500"), 0).displayName)
        assertEquals("SaskTel", OperatorResolver.resolve(context, "", listOf("302780"), 0).displayName)
        assertEquals("Eastlink", OperatorResolver.resolve(context, "", listOf("302270"), 0).displayName)
        assertEquals("US Cellular", OperatorResolver.resolve(context, "", listOf("311580"), 0).displayName)
        assertEquals("Flow", OperatorResolver.resolve(context, "", listOf("338020"), 0).displayName)
        assertEquals("bmobile", OperatorResolver.resolve(context, "", listOf("37412"), 0).displayName)
        assertEquals("+Móvil", OperatorResolver.resolve(context, "", listOf("71401"), 0).displayName)
        assertEquals("Altice", OperatorResolver.resolve(context, "", listOf("37001"), 0).displayName)
        assertEquals("operator_t_mobile", resolvePlmn("310260"))
        assertEquals("operator_verizon", resolvePlmn("311480"))
        assertEquals("operator_att", resolvePlmn("310150"))

        assertEquals("operator_telcel", resolvePlmn("334020"))
        assertEquals("operator_tim", resolvePlmn("72402"))
        assertEquals("operator_vivo", resolvePlmn("72406"))
        assertEquals("operator_claro", resolvePlmn("72405"))
        assertEquals("operator_algar", resolvePlmn("72432"))

        assertEquals("Digicel", OperatorResolver.resolve(context, "", listOf("342750"), 0).displayName)
        assertEquals("Digicel", OperatorResolver.resolve(context, "", listOf("346050"), 0).displayName)
        assertEquals("Flow", OperatorResolver.resolve(context, "", listOf("346140"), 0).displayName)
        assertEquals("Flow", OperatorResolver.resolve(context, "", listOf("348170"), 0).displayName)
        assertEquals("Digicel", OperatorResolver.resolve(context, "", listOf("348770"), 0).displayName)
        assertEquals("Digicel", OperatorResolver.resolve(context, "", listOf("352030"), 0).displayName)
        assertEquals("Flow", OperatorResolver.resolve(context, "", listOf("352110"), 0).displayName)
        assertEquals("Flow", OperatorResolver.resolve(context, "", listOf("354860"), 0).displayName)
        assertEquals("Claro", OperatorResolver.resolve(context, "", listOf("37002"), 0).displayName)
        assertEquals("Viva", OperatorResolver.resolve(context, "", listOf("37004"), 0).displayName)
        assertEquals("operator_viva_dr", resolvePlmn("37004"))
        assertEquals("Digicel", OperatorResolver.resolve(context, "", listOf("37413"), 0).displayName)
        assertEquals("WOM", OperatorResolver.resolve(context, "", listOf("732360"), 0).displayName)
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

        assertEquals("Tigo", OperatorResolver.resolve(context, "", listOf("70402"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("70403"), 0).displayName)
        assertEquals("Digicel", OperatorResolver.resolve(context, "", listOf("70602"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("71030"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("71204"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("71402"), 0).displayName)
        assertEquals("Digicel", OperatorResolver.resolve(context, "", listOf("71404"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("714020"), 0).displayName)
        assertEquals("Entel", OperatorResolver.resolve(context, "", listOf("73602"), 0).displayName)
        assertEquals("Tigo", OperatorResolver.resolve(context, "", listOf("74404"), 0).displayName)

        assertEquals("Kölbi", OperatorResolver.resolve(context, "", listOf("71201"), 0).displayName)
        assertEquals("Kölbi", OperatorResolver.resolve(context, "", listOf("71202"), 0).displayName)
        assertEquals("Antel", OperatorResolver.resolve(context, "", listOf("74800"), 0).displayName)
        assertEquals("Antel", OperatorResolver.resolve(context, "", listOf("74801"), 0).displayName)
        assertEquals("Fido", OperatorResolver.resolve(context, "", listOf("302370"), 0).displayName)
        assertEquals("Freedom", OperatorResolver.resolve(context, "", listOf("302490"), 0).displayName)
        assertEquals("operator_kolbi", OperatorResolver.getOperatorBrandKey("Kolbi"))
        assertEquals("operator_fido", OperatorResolver.getOperatorBrandKey("Fido"))
        assertEquals("operator_freedom", OperatorResolver.getOperatorBrandKey("Freedom Mobile"))
        assertEquals("operator_antel", OperatorResolver.getOperatorBrandKey("Antel"))
        assertEquals("operator_viva_dr", OperatorResolver.getOperatorBrandKey("Viva DR"))
        assertEquals(R.drawable.ic_operator_viva_dr, SimUtils.getOperatorIconResource("operator_viva_dr"))
    }

    @Test
    fun `test absent Americas invented PLMNs fallback to generic format`() {
        assertEquals("Operator (MCC:311 MNC:001)", resolveFallback("311001"))
        assertEquals("Operator (MCC:312 MNC:060)", resolveFallback("312060"))
        assertEquals("Operator (MCC:316 MNC:041)", resolveFallback("316041"))
        assertEquals("Operator (MCC:338 MNC:070)", resolveFallback("338070"))
        assertEquals("Operator (MCC:348 MNC:110)", resolveFallback("348110"))
        assertEquals("Operator (MCC:354 MNC:110)", resolveFallback("354110"))
        assertEquals("Operator (MCC:368 MNC:110)", resolveFallback("368110"))
    }

    @Test
    fun `test Americas brand tokens resolve to resource strings`() {
        assertEquals(R.string.operator_telcel, OperatorResolver.getOperatorResourceId("telcel") as Int)
        assertEquals(R.string.operator_vivo, OperatorResolver.getOperatorResourceId("vivo") as Int)
        assertEquals(R.string.operator_vivo, OperatorResolver.getOperatorResourceId("telefonica vivo") as Int)
        assertEquals(R.string.operator_vivo, OperatorResolver.getOperatorResourceId("vivo brasil") as Int)
        assertEquals(R.string.operator_algar, OperatorResolver.getOperatorResourceId("algar") as Int)
        assertEquals(R.string.operator_algar, OperatorResolver.getOperatorResourceId("algar telecom") as Int)
        assertEquals(R.string.operator_kolbi, OperatorResolver.getOperatorResourceId("kolbi") as Int)
        assertEquals(R.string.operator_antel, OperatorResolver.getOperatorResourceId("antel") as Int)
        assertEquals(R.string.operator_fido, OperatorResolver.getOperatorResourceId("fido") as Int)
        assertEquals(R.string.operator_freedom, OperatorResolver.getOperatorResourceId("freedom") as Int)
    }

    @Test
    fun `test Americas operator icon resolution`() {
        assertEquals(R.drawable.ic_operator_telcel, SimUtils.getOperatorIconResource("telcel"))
        assertEquals(R.drawable.ic_operator_vivo, SimUtils.getOperatorIconResource("vivo"))
        assertEquals(R.drawable.ic_operator_algar, SimUtils.getOperatorIconResource("algar"))
        assertEquals(R.drawable.ic_operator_rogers, SimUtils.getOperatorIconResource("rogers"))
        assertEquals(R.drawable.ic_operator_bell, SimUtils.getOperatorIconResource("bell"))
        assertEquals(R.drawable.ic_operator_kolbi, SimUtils.getOperatorIconResource("kolbi"))
        assertEquals(R.drawable.ic_operator_antel, SimUtils.getOperatorIconResource("antel"))
        assertEquals(R.drawable.ic_operator_fido, SimUtils.getOperatorIconResource("fido"))
        assertEquals(R.drawable.ic_operator_freedom, SimUtils.getOperatorIconResource("freedom"))
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
