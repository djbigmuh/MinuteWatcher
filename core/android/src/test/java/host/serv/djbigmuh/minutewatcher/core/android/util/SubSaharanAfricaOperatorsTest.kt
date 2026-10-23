package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubSaharanAfricaOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Sub-Saharan Africa PLMN database mappings`() {
        assertEquals("Telecel", OperatorResolver.resolve(context, "", listOf("62002"), 0).displayName)
        assertEquals("AirtelTigo", OperatorResolver.resolve(context, "", listOf("62003"), 0).displayName)
        assertEquals("Yas", OperatorResolver.resolve(context, "", listOf("64002"), 0).displayName)
        assertEquals("Moov", OperatorResolver.resolve(context, "", listOf("61202"), 0).displayName)
        assertEquals("Expresso", OperatorResolver.resolve(context, "", listOf("60803"), 0).displayName)
        assertEquals("Ethio Telecom", OperatorResolver.resolve(context, "", listOf("63601"), 0).displayName)
        assertEquals("Econet", OperatorResolver.resolve(context, "", listOf("64804"), 0).displayName)
        assertEquals("NetOne", OperatorResolver.resolve(context, "", listOf("64801"), 0).displayName)
        assertEquals("Unitel", OperatorResolver.resolve(context, "", listOf("63102"), 0).displayName)
        assertEquals("Movicel", OperatorResolver.resolve(context, "", listOf("63104"), 0).displayName)
        assertEquals("Mascom", OperatorResolver.resolve(context, "", listOf("65201"), 0).displayName)
        assertEquals("MTC", OperatorResolver.resolve(context, "", listOf("64901"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("60801"), 0).displayName)
        assertEquals("Free", OperatorResolver.resolve(context, "", listOf("60802"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("61101"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("61104"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("61203"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("61205"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("62001"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("62401"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("62402"), 0).displayName)
        assertEquals("Airtel", OperatorResolver.resolve(context, "", listOf("62803"), 0).displayName)
        assertEquals("Airtel", OperatorResolver.resolve(context, "", listOf("62901"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("62910"), 0).displayName)
        assertEquals("Vodacom", OperatorResolver.resolve(context, "", listOf("63001"), 0).displayName)
        assertEquals("Airtel", OperatorResolver.resolve(context, "", listOf("63002"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("63089"), 0).displayName)
        assertEquals("Zain", OperatorResolver.resolve(context, "", listOf("63401"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("63402"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("63510"), 0).displayName)
        assertEquals("Airtel", OperatorResolver.resolve(context, "", listOf("63513"), 0).displayName)
        assertEquals("Safaricom", OperatorResolver.resolve(context, "", listOf("63602"), 0).displayName)
        assertEquals("Yas", OperatorResolver.resolve(context, "", listOf("64002"), 0).displayName)
        assertEquals("Vodacom", OperatorResolver.resolve(context, "", listOf("64004"), 0).displayName)
        assertEquals("Airtel", OperatorResolver.resolve(context, "", listOf("64005"), 0).displayName)
        assertEquals("Airtel", OperatorResolver.resolve(context, "", listOf("64101"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("64110"), 0).displayName)
        assertEquals("Vodacom", OperatorResolver.resolve(context, "", listOf("64304"), 0).displayName)
        assertEquals("Airtel", OperatorResolver.resolve(context, "", listOf("64501"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("64502"), 0).displayName)
        assertEquals("Airtel", OperatorResolver.resolve(context, "", listOf("64601"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("64602"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("64700"), 0).displayName)
        assertEquals("Free", OperatorResolver.resolve(context, "", listOf("64703"), 0).displayName)
        assertEquals("SFR", OperatorResolver.resolve(context, "", listOf("64710"), 0).displayName)
        assertEquals("Airtel", OperatorResolver.resolve(context, "", listOf("65010"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("65202"), 0).displayName)

        val res61402 = OperatorResolver.resolve(context, "", listOf("61402"), 0)
        assertEquals("Airtel", res61402.displayName)
        assertEquals("operator_airtel", res61402.brandKey)

        val res61403 = OperatorResolver.resolve(context, "", listOf("61403"), 0)
        assertEquals("Moov", res61403.displayName)
        assertEquals("operator_moov", res61403.brandKey)

        val res61404 = OperatorResolver.resolve(context, "", listOf("61404"), 0)
        assertEquals("Orange", res61404.displayName)
        assertEquals("operator_orange", res61404.brandKey)

        val res61905 = OperatorResolver.resolve(context, "", listOf("61905"), 0)
        assertEquals(context.getString(R.string.operator_africell_sl), res61905.displayName)
        assertEquals("operator_africell_sl", res61905.brandKey)

        val res61905Named = OperatorResolver.resolve(context, "Africell", listOf("61905"), 0)
        assertEquals("operator_africell_sl", res61905Named.brandKey)

        val res61907 = OperatorResolver.resolve(context, "", listOf("61907"), 0)
        assertEquals("operator_qcell_sl", res61907.brandKey)
    }
}
