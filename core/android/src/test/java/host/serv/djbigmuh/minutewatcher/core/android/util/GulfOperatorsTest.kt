package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GulfOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Gulf PLMN database mappings`() {
        assertEquals("STC", resolveDisplayName("42001"))
        assertEquals("Mobily", resolveDisplayName("42003"))
        assertEquals("Etisalat", resolveDisplayName("42402"))
        assertEquals("du", resolveDisplayName("42403"))
    }

    @Test
    fun `test e& brand token resolves to Etisalat resource string`() {
        assertEquals(R.string.operator_etisalat, OperatorResolver.getOperatorResourceId("e&") as Int)
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
