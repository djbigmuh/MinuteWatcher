package com.github.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.resources.R
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

        val res64201 = OperatorResolver.resolve(context, "", listOf("64201"), 0)
        assertEquals("operator_econet", res64201.brandKey)

        val res64207 = OperatorResolver.resolve(context, "", listOf("64207"), 0)
        assertEquals("operator_smart_bi", res64207.brandKey)

        val res64208 = OperatorResolver.resolve(context, "", listOf("64208"), 0)
        assertEquals(context.getString(R.string.operator_lumitel), res64208.displayName)
        assertEquals("operator_lumitel", res64208.brandKey)

        val res64282 = OperatorResolver.resolve(context, "", listOf("64282"), 0)
        assertEquals("operator_econet", res64282.brandKey)

        val res61301 = OperatorResolver.resolve(context, "", listOf("61301"), 0)
        assertEquals("operator_moov", res61301.brandKey)

        val res61302 = OperatorResolver.resolve(context, "", listOf("61302"), 0)
        assertEquals("Orange", res61302.displayName)
        assertEquals("operator_orange", res61302.brandKey)

        val res61303 = OperatorResolver.resolve(context, "", listOf("61303"), 0)
        assertEquals("operator_telecel", res61303.brandKey)

        val res61001 = OperatorResolver.resolve(context, "", listOf("61001"), 0)
        assertEquals("operator_moov", res61001.brandKey)

        assertEquals("operator_telkom_ke", OperatorResolver.resolve(context, "", listOf("63907"), 0).brandKey)
        assertEquals("operator_nexttel", OperatorResolver.resolve(context, "", listOf("62404"), 0).brandKey)
        assertEquals("operator_africell_cd", OperatorResolver.resolve(context, "", listOf("63090"), 0).brandKey)
        assertEquals("operator_africell_ao", OperatorResolver.resolve(context, "", listOf("63105"), 0).brandKey)
        assertEquals("operator_cellcom_gn", OperatorResolver.resolve(context, "", listOf("61105"), 0).brandKey)
        assertEquals("operator_togocel", OperatorResolver.resolve(context, "", listOf("61501"), 0).brandKey)
        assertEquals("operator_celtiis", OperatorResolver.resolve(context, "", listOf("61607"), 0).brandKey)
        assertEquals("operator_telecel_cf", OperatorResolver.resolve(context, "", listOf("62302"), 0).brandKey)
        assertEquals("operator_utl", OperatorResolver.resolve(context, "", listOf("64111"), 0).brandKey)
        assertEquals("operator_telesom", OperatorResolver.resolve(context, "", listOf("63701"), 0).brandKey)
        assertEquals("operator_airtel", OperatorResolver.resolve(context, "", listOf("63514"), 0).brandKey)
        assertEquals("operator_eswatini_mobile", OperatorResolver.resolve(context, "", listOf("65302"), 0).brandKey)
        assertEquals("operator_moov", OperatorResolver.resolve(context, "", listOf("62802"), 0).brandKey)
        assertEquals("operator_inwi", OperatorResolver.resolve(context, "", listOf("60405"), 0).brandKey)
        assertEquals("operator_telecel_ml", OperatorResolver.resolve(context, "", listOf("61003"), 0).brandKey)
        assertEquals("operator_africell_sl", OperatorResolver.resolve(context, "", listOf("61902"), 0).brandKey)
        assertEquals("operator_sierratel", OperatorResolver.resolve(context, "", listOf("61906"), 0).brandKey)
        assertEquals("operator_tawali", OperatorResolver.resolve(context, "", listOf("62202"), 0).brandKey)
        assertEquals("operator_salaam_td", OperatorResolver.resolve(context, "", listOf("62207"), 0).brandKey)
        assertEquals("operator_camtel", OperatorResolver.resolve(context, "", listOf("62403"), 0).brandKey)
        assertEquals("operator_airtel", OperatorResolver.resolve(context, "", listOf("62907"), 0).brandKey)
        assertEquals("operator_africell_ug", OperatorResolver.resolve(context, "", listOf("64114"), 0).brandKey)
        assertEquals("operator_airtel", OperatorResolver.resolve(context, "", listOf("64122"), 0).brandKey)
        assertEquals("operator_only", OperatorResolver.resolve(context, "", listOf("64702"), 0).brandKey)
        assertEquals("operator_mattel", OperatorResolver.resolve(context, "", listOf("60901"), 0).brandKey)
        assertEquals("operator_mauritel", OperatorResolver.resolve(context, "", listOf("60910"), 0).brandKey)
        assertEquals("operator_somafone", OperatorResolver.resolve(context, "", listOf("63704"), 0).brandKey)
        assertEquals("operator_switch_na", OperatorResolver.resolve(context, "", listOf("64902"), 0).brandKey)
        assertEquals("operator_access_mw", OperatorResolver.resolve(context, "", listOf("65002"), 0).brandKey)
        assertEquals("operator_sure", OperatorResolver.resolve(context, "", listOf("65801"), 0).brandKey)

        assertEquals(
            R.string.operator_telecel_ml,
            OperatorResolver.getOperatorResourceId("telecel mali") as Int,
        )
        assertEquals(
            R.string.operator_sierratel,
            OperatorResolver.getOperatorResourceId("sierratel") as Int,
        )
        assertEquals(
            R.string.operator_access_mw,
            OperatorResolver.getOperatorResourceId("access communications") as Int,
        )
        assertEquals(
            R.string.operator_switch_na,
            OperatorResolver.getOperatorResourceId("switch namibia") as Int,
        )
    }
}
