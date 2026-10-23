package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
        assertEquals("e&", resolveDisplayName("42402"))
        assertEquals("du", resolveDisplayName("42403"))

        assertEquals("Cellcom", resolveDisplayName("42502"))
        assertEquals("Partner", resolveDisplayName("42501"))
        assertEquals("Pelephone", resolveDisplayName("42503"))
        assertEquals("Hot Mobile", resolveDisplayName("42507"))
        assertEquals("WE", resolveDisplayName("60204"))
        assertEquals("Ooredoo", resolveDisplayName("41903"))
        assertEquals("Ooredoo", resolveDisplayName("42203"))
        assertEquals("Ooredoo", resolveDisplayName("42506"))
        assertEquals("Ooredoo", resolveDisplayName("42701"))
        assertEquals("Ooredoo", resolveDisplayName("60303"))
        assertEquals("Ooredoo", resolveDisplayName("41405"))
        assertEquals("MTN", resolveDisplayName("41702"))
        assertEquals("operator_vodafone", OperatorResolver.resolve(context, "", listOf("42206"), 0).brandKey)
    }

    @Test
    fun `test e& brand token resolves to Etisalat resource string`() {
        assertEquals("operator_e_and", OperatorResolver.getOperatorBrandKey("e&"))
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
