package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SouthernEuropeTurkeyOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Southern Europe and Turkey PLMN database mappings`() {
        assertEquals("TIM", resolveDisplayName("22201"))
        assertEquals("One", resolveDisplayName("27601"))
        assertEquals("One", resolveDisplayName("29701"))
        assertEquals("m:tel", resolveDisplayName("21805"))
        assertEquals("m:tel", resolveDisplayName("29703"))
        assertEquals("BH Telecom", resolveDisplayName("21890"))
        assertEquals("HT Eronet", resolveDisplayName("21803"))
        assertEquals("Vala", resolveDisplayName("22101"))
        assertEquals("IPKO", resolveDisplayName("22102"))
        assertEquals("Fastweb", resolveDisplayName("22208"))
        assertEquals("PosteMobile", resolveDisplayName("22233"))
        assertEquals("Vodafone", resolveDisplayName("22206"))
        assertEquals("Digi", resolveDisplayName("22236"))
        assertEquals("WindTre", resolveDisplayName("22237"))
        assertEquals("TIM", resolveDisplayName("22243"))
        assertEquals("TIM", resolveDisplayName("22248"))
        assertEquals("Digi", resolveDisplayName("26802"))
        assertEquals("Lycamobile", resolveDisplayName("26804"))
        assertEquals("MEO", resolveDisplayName("26806"))
        assertEquals("Vodafone", resolveDisplayName("26891"))
        assertEquals("A1", resolveDisplayName("29403"))
        assertEquals("Iliad", resolveDisplayName("22250"))
        assertEquals("operator_coop_voce", OperatorResolver.resolve(context, "", listOf("22253"), 0).brandKey)
        assertEquals("operator_spusu", OperatorResolver.resolve(context, "", listOf("22256"), 0).brandKey)
        assertEquals("Cosmote", resolveDisplayName("20201"))
        assertEquals("Nova", OperatorResolver.resolve(context, "", listOf("20209"), 0).displayName)
        assertEquals("Nova", OperatorResolver.resolve(context, "", listOf("20210"), 0).displayName)
        assertEquals("operator_nova_gr", OperatorResolver.getOperatorBrandKey("Nova Greece"))
        assertEquals("Turkcell", resolveDisplayName("28601"))

        assertEquals("Vivacom", OperatorResolver.resolve(context, "", listOf("28403"), 0).displayName)
        assertEquals("operator_vivacom", OperatorResolver.getOperatorBrandKey("Vivacom"))

        assertEquals("GO", OperatorResolver.resolve(context, "", listOf("27821"), 0).displayName)
        assertEquals("operator_go", OperatorResolver.resolve(context, "", listOf("27821"), 0).brandKey)

        assertEquals("Yettel", OperatorResolver.resolve(context, "", listOf("22001"), 0).displayName)
        assertEquals("MTS", OperatorResolver.resolve(context, "", listOf("22003"), 0).displayName)
        assertEquals("A1", OperatorResolver.resolve(context, "", listOf("22005"), 0).displayName)
        assertEquals("epic", OperatorResolver.resolve(context, "", listOf("27801"), 0).displayName)
        assertEquals("Kena Mobile", resolveDisplayName("22207"))
    }

    @Test
    fun `test Southern Europe and Turkey brand tokens resolve to resource strings`() {
        assertEquals(R.string.operator_iliad, OperatorResolver.getOperatorResourceId("iliad") as Int)
        assertEquals(R.string.operator_iliad, OperatorResolver.getOperatorResourceId("iliad italia") as Int)
        assertEquals(R.string.operator_cosmote, OperatorResolver.getOperatorResourceId("cosmote") as Int)
        assertEquals(R.string.operator_vivacom, OperatorResolver.getOperatorResourceId("vivacom") as Int)
        assertEquals(R.string.operator_go, OperatorResolver.getOperatorResourceId("go malta") as Int)
        assertEquals(R.string.operator_coop_voce, OperatorResolver.getOperatorResourceId("coop voce") as Int)
        assertEquals(R.string.operator_spusu, OperatorResolver.getOperatorResourceId("spusu") as Int)
        assertEquals(R.string.operator_kena, OperatorResolver.getOperatorResourceId("kena") as Int)
        assertEquals(R.string.operator_postemobile, OperatorResolver.getOperatorResourceId("postemobile") as Int)
    }

    @Test
    fun `test Southern Europe and Turkey operator icon resolution`() {
        assertEquals(R.drawable.ic_operator_iliad, SimUtils.getOperatorIconResource("iliad"))
        assertEquals(R.drawable.ic_operator_cosmote, SimUtils.getOperatorIconResource("cosmote"))
        assertEquals(R.drawable.ic_operator_vivacom, SimUtils.getOperatorIconResource("vivacom"))
        assertEquals(R.drawable.ic_operator_go, SimUtils.getOperatorIconResource("go malta"))
        assertEquals(R.drawable.ic_operator_coop_voce, SimUtils.getOperatorIconResource("coop voce"))
        assertEquals(R.drawable.ic_operator_spusu, SimUtils.getOperatorIconResource("spusu"))
        assertEquals(R.drawable.ic_operator_kena, SimUtils.getOperatorIconResource("kena"))
        assertEquals(R.drawable.ic_operator_postemobile, SimUtils.getOperatorIconResource("postemobile"))
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
