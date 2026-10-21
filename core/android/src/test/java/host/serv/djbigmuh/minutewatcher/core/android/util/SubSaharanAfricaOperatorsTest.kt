package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
        assertEquals("Ooredoo", OperatorResolver.resolve(context, "", listOf("60503"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("60801"), 0).displayName)
        assertEquals("Free", OperatorResolver.resolve(context, "", listOf("60802"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("61101"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("61104"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("61203"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("61205"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("62001"), 0).displayName)
        assertEquals("MTN", OperatorResolver.resolve(context, "", listOf("62401"), 0).displayName)
        assertEquals("Orange", OperatorResolver.resolve(context, "", listOf("62402"), 0).displayName)
        assertEquals("Airtel", OperatorResolver.resolve(context, "", listOf("62804"), 0).displayName)
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
        assertEquals("Tigo", OperatorResolver.resolve(context, "", listOf("64002"), 0).displayName)
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
    }
}
