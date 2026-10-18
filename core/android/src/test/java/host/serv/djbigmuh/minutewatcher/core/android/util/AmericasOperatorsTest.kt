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
