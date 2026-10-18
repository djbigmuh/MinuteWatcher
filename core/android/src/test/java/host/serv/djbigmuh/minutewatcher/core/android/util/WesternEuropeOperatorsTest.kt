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
        assertEquals("operator_odido", resolvePlmn("20416"))
        assertEquals("operator_ee", resolvePlmn("23430"))
        assertEquals("operator_vodafone", resolvePlmn("23415"))
        assertEquals("operator_proximus", resolvePlmn("20601"))
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
    }

    @Test
    fun `test Western Europe operator icon resolution`() {
        assertEquals(R.drawable.ic_operator_free, SimUtils.getOperatorIconResource("free"))
        assertEquals(R.drawable.ic_operator_odido, SimUtils.getOperatorIconResource("odido"))
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
