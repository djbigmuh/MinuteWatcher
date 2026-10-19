package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WesternEuropeOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Western Europe PLMN database mappings`() {
        assertEquals("Free", resolveDisplayName("20815"))
        assertEquals("Lycamobile", resolveDisplayName("20409"))
        assertEquals("Telekom", resolveDisplayName("23102"))
        assertEquals("Telekom", resolveDisplayName("29702"))
        assertEquals("operator_odido", resolvePlmn("20416"))
        assertEquals("operator_ee", resolvePlmn("23430"))
        assertEquals("operator_vodafone", resolvePlmn("23415"))
        assertEquals("operator_proximus", resolvePlmn("20601"))

        assertEquals("MEO", OperatorResolver.resolve(context, "", listOf("26880"), 0).displayName)
        assertEquals("operator_meo", OperatorResolver.getOperatorBrandKey("MEO"))

        assertEquals("Eir", OperatorResolver.resolve(context, "", listOf("27203"), 0).displayName)
        assertEquals("Eir", OperatorResolver.resolve(context, "", listOf("27207"), 0).displayName)
        assertEquals("operator_eir", OperatorResolver.getOperatorBrandKey("Eir"))

        assertEquals("Maroc Telecom", OperatorResolver.resolve(context, "", listOf("60401"), 0).displayName)
        assertEquals("operator_maroc_telecom", OperatorResolver.getOperatorBrandKey("Maroc Telecom"))

        assertEquals("Tunisie Telecom", OperatorResolver.resolve(context, "", listOf("60502"), 0).displayName)
        assertEquals("operator_tunisie_telecom", OperatorResolver.getOperatorBrandKey("Tunisie Telecom"))

        assertEquals("Mobilis", OperatorResolver.resolve(context, "", listOf("60301"), 0).displayName)
        assertEquals("operator_mobilis", OperatorResolver.getOperatorBrandKey("Mobilis"))

        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("62130"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("65510"), 0).displayName)
        assertEquals("operator_mtn", OperatorResolver.getOperatorBrandKey("MTN"))

        assertEquals("Vodacom", OperatorResolver.resolve(context, "", listOf("65501"), 0).displayName)
        assertEquals("operator_vodacom", OperatorResolver.getOperatorBrandKey("Vodacom"))

        assertEquals("Safaricom", OperatorResolver.resolve(context, "", listOf("63902"), 0).displayName)
        assertEquals("operator_safaricom", OperatorResolver.getOperatorBrandKey("Safaricom"))

        assertEquals("Glo", OperatorResolver.resolve(context, "", listOf("62150"), 0).displayName)
        assertEquals("operator_glo", OperatorResolver.getOperatorBrandKey("Glo"))
    }

    @Test
    fun `test removed 23450 PLMN does not resolve to EE`() {
        assertNotEquals("operator_ee", resolvePlmn("23450"))
    }

    @Test
    fun `test Western Europe brand tokens resolve to resource strings`() {
        assertEquals(R.string.operator_free, OperatorResolver.getOperatorResourceId("free") as Int)
        assertEquals(R.string.operator_free, OperatorResolver.getOperatorResourceId("free mobile") as Int)
        assertEquals(R.string.operator_odido, OperatorResolver.getOperatorResourceId("odido") as Int)
        assertEquals(R.string.operator_meo, OperatorResolver.getOperatorResourceId("meo") as Int)
        assertEquals(R.string.operator_eir, OperatorResolver.getOperatorResourceId("eir") as Int)
        assertEquals(R.string.operator_maroc_telecom, OperatorResolver.getOperatorResourceId("maroc telecom") as Int)
        assertEquals(
            R.string.operator_tunisie_telecom,
            OperatorResolver.getOperatorResourceId("tunisie telecom") as Int,
        )
        assertEquals(R.string.operator_mobilis, OperatorResolver.getOperatorResourceId("mobilis") as Int)
        assertEquals(R.string.operator_mtn, OperatorResolver.getOperatorResourceId("mtn") as Int)
        assertEquals(R.string.operator_vodacom, OperatorResolver.getOperatorResourceId("vodacom") as Int)
        assertEquals(R.string.operator_safaricom, OperatorResolver.getOperatorResourceId("safaricom") as Int)
        assertEquals(R.string.operator_glo, OperatorResolver.getOperatorResourceId("glo") as Int)
    }

    @Test
    fun `test Western Europe operator icon resolution`() {
        assertEquals(R.drawable.ic_operator_free, SimUtils.getOperatorIconResource("free"))
        assertEquals(R.drawable.ic_operator_odido, SimUtils.getOperatorIconResource("odido"))
        assertEquals(R.drawable.ic_operator_meo, SimUtils.getOperatorIconResource("meo"))
        assertEquals(R.drawable.ic_operator_eir, SimUtils.getOperatorIconResource("eir"))
        assertEquals(R.drawable.ic_operator_maroc_telecom, SimUtils.getOperatorIconResource("maroc telecom"))
        assertEquals(R.drawable.ic_operator_tunisie_telecom, SimUtils.getOperatorIconResource("tunisie telecom"))
        assertEquals(R.drawable.ic_operator_mobilis, SimUtils.getOperatorIconResource("mobilis"))
        assertEquals(R.drawable.ic_operator_mtn, SimUtils.getOperatorIconResource("mtn"))
        assertEquals(R.drawable.ic_operator_vodacom, SimUtils.getOperatorIconResource("vodacom"))
        assertEquals(R.drawable.ic_operator_safaricom, SimUtils.getOperatorIconResource("safaricom"))
        assertEquals(R.drawable.ic_operator_glo, SimUtils.getOperatorIconResource("glo"))
    }

    private fun resolvePlmn(plmn: String): String? {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.brandKey
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
