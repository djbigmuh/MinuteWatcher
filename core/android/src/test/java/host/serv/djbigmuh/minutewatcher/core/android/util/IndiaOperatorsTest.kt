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
class IndiaOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test India PLMN database mappings`() {
        assertEquals("Airtel", resolveDisplayName("40402"))
        assertEquals("Airtel", resolveDisplayName("40410"))
        assertEquals("Vi", resolveDisplayName("40401"))
        assertEquals("Vi", resolveDisplayName("40411"))
        assertEquals("Jio", resolveDisplayName("405840"))
        assertEquals("BSNL", resolveDisplayName("40434"))
        assertEquals("BSNL", resolveDisplayName("40451"))
    }

    @Test
    fun `test BSNL PLMNs do not resolve to Airtel or Vi`() {
        assertNotEquals("operator_airtel", resolvePlmn("40434"))
        assertNotEquals("operator_vi", resolvePlmn("40434"))
    }

    @Test
    fun `test unassigned invented India PLMNs fallback to generic format`() {
        assertEquals("Operator (MCC:404 MNC:999)", resolveFallback("404999"))
        assertEquals("Operator (MCC:405 MNC:000)", resolveFallback("405000"))
    }

    @Test
    fun `test BSNL brand token resolves to resource string and icon`() {
        assertEquals(R.string.operator_bsnl, OperatorResolver.getOperatorResourceId("bsnl") as Int)
        assertEquals(R.drawable.ic_operator_bsnl, SimUtils.getOperatorIconResource("bsnl"))
    }

    private fun resolvePlmn(plmn: String): String? {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.brandKey
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }

    private fun resolveFallback(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
