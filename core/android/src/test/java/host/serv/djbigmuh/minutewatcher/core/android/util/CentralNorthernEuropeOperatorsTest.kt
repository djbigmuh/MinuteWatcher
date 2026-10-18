package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CentralNorthernEuropeOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Central and Northern Europe PLMN database mappings`() {
        assertEquals("Telekom", resolveDisplayName("26201"))
        assertEquals("1&1", resolveDisplayName("26223"))
        assertEquals("Magenta", resolveDisplayName("23203"))
        assertEquals("Yettel", resolveDisplayName("21601"))
        assertEquals("operator_telia", resolvePlmn("24801"))
        assertEquals("operator_tele2", resolvePlmn("24603"))
        assertEquals("operator_tele2", resolvePlmn("24007"))
    }

    @Test
    fun `test Central and Northern Europe brand tokens resolve to resource strings`() {
        assertEquals(R.string.operator_magenta, OperatorResolver.getOperatorResourceId("magenta") as Int)
        assertEquals(R.string.operator_magenta, OperatorResolver.getOperatorResourceId("magenta telekom") as Int)
        assertEquals(R.string.operator_oneandone, OperatorResolver.getOperatorResourceId("1&1") as Int)
        assertEquals(R.string.operator_oneandone, OperatorResolver.getOperatorResourceId("1und1") as Int)
        assertEquals(R.string.operator_oneandone, OperatorResolver.getOperatorResourceId("eins und eins") as Int)
        assertEquals(R.string.operator_yettel, OperatorResolver.getOperatorResourceId("yettel") as Int)
    }

    @Test
    fun `test Central and Northern Europe operator icon resolution`() {
        assertEquals(R.drawable.ic_operator_magenta, SimUtils.getOperatorIconResource("magenta"))
        assertEquals(R.drawable.ic_operator_oneandone, SimUtils.getOperatorIconResource("1&1"))
        assertEquals(R.drawable.ic_operator_yettel, SimUtils.getOperatorIconResource("yettel"))
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
