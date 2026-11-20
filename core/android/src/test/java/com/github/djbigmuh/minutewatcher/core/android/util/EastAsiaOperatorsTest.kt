package com.github.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EastAsiaOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test East Asia PLMN database mappings`() {
        assertEquals("China Mobile", resolveDisplayName("46000"))
        assertEquals("China Unicom", resolveDisplayName("46001"))
        assertEquals("China Telecom", resolveDisplayName("46011"))
        assertEquals("NTT Docomo", resolveDisplayName("44010"))
        assertEquals("NTT Docomo", resolveDisplayName("44030"))
        assertEquals("SoftBank", resolveDisplayName("44020"))
        assertEquals("SoftBank", resolveDisplayName("44004"))
        assertEquals("SoftBank", resolveDisplayName("44006"))
        assertEquals("KDDI", resolveDisplayName("44050"))
        assertEquals("KDDI", resolveDisplayName("44055"))
        assertEquals("Rakuten Mobile", resolveDisplayName("44011"))
        assertEquals("SoftBank", resolveDisplayName("44101"))
        assertEquals("Koryolink", resolveDisplayName("46705"))
        assertEquals("Kang Song NET", resolveDisplayName("46706"))
        assertEquals("operator_kt", OperatorResolver.resolve(context, "", listOf("45007"), 0).brandKey)
    }

    @Test
    fun `test Rakuten brand tokens resolve to resource string and icon`() {
        assertEquals(R.string.operator_rakuten, OperatorResolver.getOperatorResourceId("rakuten") as Int)
        assertEquals(R.string.operator_rakuten, OperatorResolver.getOperatorResourceId("rakuten mobile") as Int)
        assertEquals(R.drawable.ic_operator_rakuten, SimUtils.getOperatorIconResource("rakuten"))
    }

    @Test
    fun `test Koryolink brand tokens resolve to resource string and icon`() {
        assertEquals(R.string.operator_koryolink, OperatorResolver.getOperatorResourceId("koryolink") as Int)
        assertEquals(R.drawable.ic_operator_koryolink, SimUtils.getOperatorIconResource("koryolink"))
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
