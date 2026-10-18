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
        assertEquals("Iliad", resolveDisplayName("22250"))
        assertEquals("Cosmote", resolveDisplayName("20201"))
        assertEquals("Turkcell", resolveDisplayName("28601"))
    }

    @Test
    fun `test Southern Europe and Turkey brand tokens resolve to resource strings`() {
        assertEquals(R.string.operator_iliad, OperatorResolver.getOperatorResourceId("iliad") as Int)
        assertEquals(R.string.operator_iliad, OperatorResolver.getOperatorResourceId("iliad italia") as Int)
        assertEquals(R.string.operator_cosmote, OperatorResolver.getOperatorResourceId("cosmote") as Int)
    }

    @Test
    fun `test Southern Europe and Turkey operator icon resolution`() {
        assertEquals(R.drawable.ic_operator_iliad, SimUtils.getOperatorIconResource("iliad"))
        assertEquals(R.drawable.ic_operator_cosmote, SimUtils.getOperatorIconResource("cosmote"))
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
