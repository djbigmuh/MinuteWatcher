package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OceaniaOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Oceania PLMN database mappings`() {
        assertEquals("Telstra", resolveDisplayName("50501"))
        assertEquals("Optus", resolveDisplayName("50502"))
        assertEquals("Vodafone", resolveDisplayName("50503"))
        assertEquals("One NZ", resolveDisplayName("53001"))
        assertEquals("Spark", resolveDisplayName("53005"))
        assertEquals("2degrees", resolveDisplayName("53024"))
    }

    @Test
    fun `test One NZ brand token resolves to resource string and icon`() {
        assertEquals(R.string.operator_onenz, OperatorResolver.getOperatorResourceId("one nz") as Int)
        assertEquals(R.string.operator_onenz, OperatorResolver.getOperatorResourceId("one new zealand") as Int)
        assertEquals(R.drawable.ic_operator_onenz, SimUtils.getOperatorIconResource("one nz"))
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
