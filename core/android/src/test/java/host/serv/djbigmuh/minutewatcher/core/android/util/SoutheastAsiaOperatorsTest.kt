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
class SoutheastAsiaOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Southeast Asia PLMN database mappings`() {
        assertEquals("AIS", resolveDisplayName("52001"))
        assertNotEquals("Airtel", resolveDisplayName("52001"))

        assertEquals("TrueMove H", resolveDisplayName("52004"))
        assertEquals("dtac", resolveDisplayName("52005"))

        assertEquals("MobiFone", resolveDisplayName("45201"))
        assertEquals("VinaPhone", resolveDisplayName("45202"))
        assertEquals("Viettel", resolveDisplayName("45204"))
        assertNotEquals("Vi", resolveDisplayName("45201"))

        assertEquals("Singtel", resolveDisplayName("52501"))
        assertEquals("Maxis", resolveDisplayName("50212"))
        assertEquals("CelcomDigi", resolveDisplayName("50216"))
        assertEquals("Telkomcel", resolveDisplayName("51401"))
        assertEquals("Timor Telecom", resolveDisplayName("51402"))
        assertEquals("Telemor", resolveDisplayName("51403"))
        assertEquals("dtac", resolveDisplayName("52047"))
        assertEquals("PCSB", resolveDisplayName("52802"))
    }

    @Test
    fun `test Southeast Asia brand tokens resolve to resource strings and icons`() {
        assertEquals(R.string.operator_ais, OperatorResolver.getOperatorResourceId("ais") as Int)
        assertEquals(R.string.operator_truemove, OperatorResolver.getOperatorResourceId("truemove") as Int)
        assertEquals(R.string.operator_dtac, OperatorResolver.getOperatorResourceId("dtac") as Int)
        assertEquals(R.string.operator_mobifone, OperatorResolver.getOperatorResourceId("mobifone") as Int)
        assertEquals(R.string.operator_vinaphone, OperatorResolver.getOperatorResourceId("vinaphone") as Int)
        assertEquals(R.string.operator_viettel, OperatorResolver.getOperatorResourceId("viettel") as Int)
        assertEquals(R.string.operator_celcomdigi, OperatorResolver.getOperatorResourceId("celcomdigi") as Int)
        assertEquals(R.string.operator_telkomcel, OperatorResolver.getOperatorResourceId("telkomcel") as Int)
        assertEquals(R.string.operator_timor_telecom, OperatorResolver.getOperatorResourceId("timor telecom") as Int)
        assertEquals(R.string.operator_telemor, OperatorResolver.getOperatorResourceId("telemor") as Int)

        assertEquals(R.drawable.ic_operator_ais, SimUtils.getOperatorIconResource("ais"))
        assertEquals(R.drawable.ic_operator_truemove, SimUtils.getOperatorIconResource("truemove"))
        assertEquals(R.drawable.ic_operator_dtac, SimUtils.getOperatorIconResource("dtac"))
        assertEquals(R.drawable.ic_operator_mobifone, SimUtils.getOperatorIconResource("mobifone"))
        assertEquals(R.drawable.ic_operator_vinaphone, SimUtils.getOperatorIconResource("vinaphone"))
        assertEquals(R.drawable.ic_operator_viettel, SimUtils.getOperatorIconResource("viettel"))
        assertEquals(R.drawable.ic_operator_celcomdigi, SimUtils.getOperatorIconResource("celcomdigi"))
        assertEquals(R.drawable.ic_operator_telkomcel, SimUtils.getOperatorIconResource("telkomcel"))
        assertEquals(R.drawable.ic_operator_timor_telecom, SimUtils.getOperatorIconResource("timor telecom"))
        assertEquals(R.drawable.ic_operator_telemor, SimUtils.getOperatorIconResource("telemor"))
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
