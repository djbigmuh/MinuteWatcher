package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsiaPacificOperatorsTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        OperatorResolver.reset()
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `mcc mnc database contains asia-pacific assignments`() {
        // SK Telecom, KT, LG U+
        assertEquals("SK Telecom", OperatorResolver.resolve(context, "", listOf("45005"), 0).displayName)
        assertEquals("KT", OperatorResolver.resolve(context, "", listOf("45002"), 0).displayName)
        assertEquals("LG U+", OperatorResolver.resolve(context, "", listOf("45006"), 0).displayName)
        // Globe, Smart, DITO
        assertEquals("Globe", OperatorResolver.resolve(context, "", listOf("51502"), 0).displayName)
        assertEquals("Smart", OperatorResolver.resolve(context, "", listOf("51503"), 0).displayName)
        assertEquals("DITO", OperatorResolver.resolve(context, "", listOf("51566"), 0).displayName)
        // Telkomsel, Indosat, XL, Smartfren, Tri
        assertEquals("Telkomsel", OperatorResolver.resolve(context, "", listOf("51010"), 0).displayName)
        assertEquals("Indosat", OperatorResolver.resolve(context, "", listOf("51001"), 0).displayName)
        assertEquals("XL", OperatorResolver.resolve(context, "", listOf("51011"), 0).displayName)
        assertEquals("Smartfren", OperatorResolver.resolve(context, "", listOf("51009"), 0).displayName)
        assertEquals("Tri", OperatorResolver.resolve(context, "", listOf("51089"), 0).displayName)
        // Taiwan
        assertEquals("Chunghwa Telecom", OperatorResolver.resolve(context, "", listOf("46692"), 0).displayName)
        assertEquals("Taiwan Mobile", OperatorResolver.resolve(context, "", listOf("46697"), 0).displayName)
        assertEquals("FarEasTone", OperatorResolver.resolve(context, "", listOf("46601"), 0).displayName)
        // Hong Kong
        assertEquals("CSL", OperatorResolver.resolve(context, "", listOf("45400"), 0).displayName)
        assertEquals("3 Hong Kong", OperatorResolver.resolve(context, "", listOf("45403"), 0).displayName)
        assertEquals("SmarTone", OperatorResolver.resolve(context, "", listOf("45406"), 0).displayName)
        // Cambodia / others
        assertEquals("Cellcard", OperatorResolver.resolve(context, "", listOf("45618"), 0).displayName)
        assertEquals("Smart Cambodia", OperatorResolver.resolve(context, "", listOf("45602"), 0).displayName)
        assertEquals("Smart Cambodia", OperatorResolver.resolve(context, "", listOf("45605"), 0).displayName)
        assertEquals("Metfone", OperatorResolver.resolve(context, "", listOf("45608"), 0).displayName)
        assertEquals("Metfone", OperatorResolver.resolve(context, "", listOf("45609"), 0).displayName)
        assertEquals("Celcom", OperatorResolver.resolve(context, "", listOf("50219"), 0).displayName)
        assertEquals("Maxis", OperatorResolver.resolve(context, "", listOf("50210"), 0).displayName)
        assertEquals("Maxis", OperatorResolver.resolve(context, "", listOf("50217"), 0).displayName)
        assertEquals("Hutch", OperatorResolver.resolve(context, "", listOf("41303"), 0).displayName)
        assertEquals("SmarTone", OperatorResolver.resolve(context, "", listOf("45500"), 0).displayName)
        assertEquals("FarEasTone", OperatorResolver.resolve(context, "", listOf("46605"), 0).displayName)
        assertEquals("Taiwan Mobile", OperatorResolver.resolve(context, "", listOf("46689"), 0).displayName)
        assertEquals("Ooredoo", OperatorResolver.resolve(context, "", listOf("47202"), 0).displayName)
        assertEquals("Vodafone", OperatorResolver.resolve(context, "", listOf("54201"), 0).displayName)
        assertEquals("Digicel", OperatorResolver.resolve(context, "", listOf("54202"), 0).displayName)
        // Japan / others
        assertEquals("Y! Mobile", OperatorResolver.resolve(context, "", listOf("44000"), 0).displayName)
        assertEquals("SoftBank", OperatorResolver.resolve(context, "", listOf("44021"), 0).displayName)
        assertEquals("au", OperatorResolver.resolve(context, "", listOf("44052"), 0).displayName)
    }

    @Test
    fun `asia-pacific brand tokens resolve to resource strings and brand keys`() {
        assertEquals("operator_sk_telecom", OperatorResolver.getOperatorBrandKey("SK Telecom"))
        assertEquals("operator_sk_telecom", OperatorResolver.getOperatorBrandKey("SK TELECOM"))
        assertEquals("operator_sk_telecom", OperatorResolver.getOperatorBrandKey("skt"))
        assertEquals("operator_kt", OperatorResolver.getOperatorBrandKey("olleh"))
        assertEquals("operator_lg_uplus", OperatorResolver.getOperatorBrandKey("lg uplus"))
        assertEquals("operator_globe", OperatorResolver.getOperatorBrandKey("globe telecom"))
        assertEquals("operator_smart", OperatorResolver.getOperatorBrandKey("smart communications"))
        assertEquals("operator_dito", OperatorResolver.getOperatorBrandKey("dito telecommunity"))
        assertEquals("operator_telkomsel", OperatorResolver.getOperatorBrandKey("simpati"))
        assertEquals("operator_indosat", OperatorResolver.getOperatorBrandKey("im3"))
        assertEquals("operator_xl_axiata", OperatorResolver.getOperatorBrandKey("xl axiata"))
        assertEquals("operator_smartfren", OperatorResolver.getOperatorBrandKey("smart fren"))
        assertEquals("operator_tri_id", OperatorResolver.getOperatorBrandKey("3 indonesia"))
        assertEquals("operator_cht", OperatorResolver.getOperatorBrandKey("chunghwa telecom"))
        assertEquals("operator_taiwan_mobile", OperatorResolver.getOperatorBrandKey("twm"))
        assertEquals("operator_fareastone", OperatorResolver.getOperatorBrandKey("far eastone"))
        assertEquals("operator_csl", OperatorResolver.getOperatorBrandKey("1o1o"))
        assertEquals("operator_three_hk", OperatorResolver.getOperatorBrandKey("three hk"))
        assertEquals("operator_smartone", OperatorResolver.getOperatorBrandKey("smart one"))
        assertEquals("operator_ymobile", OperatorResolver.getOperatorBrandKey("willcom"))
        assertEquals("operator_au_kddi", OperatorResolver.getOperatorBrandKey("au by kddi"))
        assertEquals("operator_uq_mobile", OperatorResolver.getOperatorBrandKey("uq communications"))
    }
}
