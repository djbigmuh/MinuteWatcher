package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpainOperatorsTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        OperatorResolver.reset()
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `mcc mnc database contains spanish assignments`() {
        assertEquals("Vodafone", OperatorResolver.resolve(context, "", listOf("21401"), 0).displayName)
        assertEquals("Vodafone", OperatorResolver.resolve(context, "", listOf("21406"), 0).displayName)
        assertEquals("Vodafone", OperatorResolver.resolve(context, "", listOf("21424"), 0).displayName)
        assertEquals("Vodafone", OperatorResolver.resolve(context, "", listOf("21437"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("21403"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("21409"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("21405"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("21407"), 0).displayName)
        assertEquals("Movistar", OperatorResolver.resolve(context, "", listOf("21438"), 0).displayName)
        assertEquals("Yoigo", OperatorResolver.resolve(context, "", listOf("21404"), 0).displayName)
        assertEquals("Euskaltel", OperatorResolver.resolve(context, "", listOf("21408"), 0).displayName)
        assertEquals("Telecable", OperatorResolver.resolve(context, "", listOf("21416"), 0).displayName)
        assertEquals("R", OperatorResolver.resolve(context, "", listOf("21417"), 0).displayName)
        assertEquals("simyo", OperatorResolver.resolve(context, "", listOf("21419"), 0).displayName)
        assertEquals("jazztel", OperatorResolver.resolve(context, "", listOf("21421"), 0).displayName)
        assertEquals("Digi", OperatorResolver.resolve(context, "", listOf("21422"), 0).displayName)
        assertEquals("Vodafone", OperatorResolver.resolve(context, "", listOf("26801"), 0).displayName)
    }

    @Test
    fun `spanish brand tokens resolve to brand string resources`() {
        assertEquals(R.string.operator_yoigo, OperatorResolver.getOperatorResourceId("Yoigo") as Int)
        assertEquals(R.string.operator_euskaltel, OperatorResolver.getOperatorResourceId("EUSKALTEL") as Int)
        assertEquals(R.string.operator_telecable, OperatorResolver.getOperatorResourceId("Telecable") as Int)
        assertEquals(R.string.operator_simyo, OperatorResolver.getOperatorResourceId("simyo") as Int)
        assertEquals(R.string.operator_jazztel, OperatorResolver.getOperatorResourceId("Jazztel") as Int)
        assertEquals(R.string.operator_amena, OperatorResolver.getOperatorResourceId("amena") as Int)
        assertEquals(R.string.operator_masmovil, OperatorResolver.getOperatorResourceId("MásMóvil") as Int)
        assertEquals(R.string.operator_masmovil, OperatorResolver.getOperatorResourceId("MAS MOVIL") as Int)
        assertEquals(R.string.operator_pepephone, OperatorResolver.getOperatorResourceId("pepephone") as Int)
        assertEquals(R.string.operator_lowi, OperatorResolver.getOperatorResourceId("Lowi") as Int)
        assertEquals(R.string.operator_lebara, OperatorResolver.getOperatorResourceId("Lebara") as Int)
        assertEquals(R.string.operator_lycamobile, OperatorResolver.getOperatorResourceId("Lycamobile") as Int)
        assertEquals(R.string.operator_finetwork, OperatorResolver.getOperatorResourceId("finetwork") as Int)
    }

    @Test
    fun `spanish brands resolve icons per asset availability`() {
        assertEquals(R.drawable.ic_operator_euskaltel, SimUtils.getOperatorIconResource("Euskaltel"))
        assertEquals(R.drawable.ic_operator_amena, SimUtils.getOperatorIconResource("amena"))
        assertEquals(R.drawable.ic_operator_lycamobile, SimUtils.getOperatorIconResource("Lycamobile"))
        assertEquals(R.drawable.ic_operator_yoigo, SimUtils.getOperatorIconResource("Yoigo"))
        assertEquals(R.drawable.ic_operator_telecable, SimUtils.getOperatorIconResource("Telecable"))
        assertEquals(R.drawable.ic_operator_simyo, SimUtils.getOperatorIconResource("simyo"))
        assertEquals(R.drawable.ic_operator_jazztel, SimUtils.getOperatorIconResource("jazztel"))
        assertEquals(R.drawable.ic_operator_masmovil, SimUtils.getOperatorIconResource("MásMóvil"))
        assertEquals(R.drawable.ic_operator_pepephone, SimUtils.getOperatorIconResource("pepephone"))
        assertEquals(R.drawable.ic_operator_lowi, SimUtils.getOperatorIconResource("lowi"))
        assertEquals(R.drawable.ic_operator_lebara, SimUtils.getOperatorIconResource("Lebara"))
        assertEquals(R.drawable.ic_operator_finetwork, SimUtils.getOperatorIconResource("finetwork"))
    }
}
