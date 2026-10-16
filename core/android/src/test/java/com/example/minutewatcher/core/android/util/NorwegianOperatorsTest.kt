package com.example.minutewatcher.core.android.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.resources.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NorwegianOperatorsTest {
    private val context: Context = mock()
    private val resources: Resources = mock()
    private val configuration: Configuration = mock()

    @Before
    fun setup() {
        initTestLogger(context)
        whenever(context.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(configuration)
        whenever(context.createConfigurationContext(any())).thenReturn(context)
    }

    @Test
    fun `mcc mnc database contains norwegian assignments`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val json =
            appContext.assets.open("mcc_mnc_db.json").use { input ->
                JSONObject(String(input.readBytes(), Charsets.UTF_8))
            }
        assertEquals("operator_telenor", json.getString("24201"))
        assertEquals("operator_telenor", json.getString("24212"))
        assertEquals("operator_telia", json.getString("24202"))
        assertEquals("operator_telia", json.getString("24205"))
        assertEquals("operator_telia", json.getString("24208"))
        assertEquals("operator_ice", json.getString("24206"))
        assertEquals("operator_ice", json.getString("24214"))
        assertEquals("operator_phonero", json.getString("24207"))
    }

    @Test
    fun `norwegian brand tokens resolve to brand string resources`() {
        assertEquals(R.string.operator_onecall, OperatorResolver.getOperatorResourceId("OneCall"))
        assertEquals(R.string.operator_mycall, OperatorResolver.getOperatorResourceId("MYCALL"))
        assertEquals(R.string.operator_phonero, OperatorResolver.getOperatorResourceId("Phonero"))
        assertEquals(R.string.operator_talkmore, OperatorResolver.getOperatorResourceId("Talkmore"))
        assertEquals(R.string.operator_chilimobil, OperatorResolver.getOperatorResourceId("chilimobil"))
        assertEquals(R.string.operator_fjordkraft, OperatorResolver.getOperatorResourceId("Fjordkraft Mobil"))
        assertEquals(R.string.operator_telenor, OperatorResolver.getOperatorResourceId("Telenor"))
        assertEquals(R.string.operator_telia, OperatorResolver.getOperatorResourceId("Telia Norge"))
        assertEquals(R.string.operator_ice, OperatorResolver.getOperatorResourceId("Ice Norge"))
    }

    @Test
    fun `norwegian mvno brands use neutral icon`() {
        assertEquals(R.drawable.ic_unknown, SimUtils.getOperatorIconResource("OneCall"))
        assertEquals(R.drawable.ic_unknown, SimUtils.getOperatorIconResource("MyCall"))
        assertEquals(R.drawable.ic_unknown, SimUtils.getOperatorIconResource("Phonero"))
        assertEquals(R.drawable.ic_unknown, SimUtils.getOperatorIconResource("Talkmore"))
        assertEquals(R.drawable.ic_unknown, SimUtils.getOperatorIconResource("Chilimobil"))
        assertEquals(R.drawable.ic_unknown, SimUtils.getOperatorIconResource("Fjordkraft Mobil"))
    }

    @Test
    fun `norwegian network brands keep their icons`() {
        assertEquals(R.drawable.ic_operator_telenor, SimUtils.getOperatorIconResource("Telenor"))
        assertEquals(R.drawable.ic_operator_telia, SimUtils.getOperatorIconResource("Telia Norge"))
        assertEquals(R.drawable.ic_operator_ice, SimUtils.getOperatorIconResource("ice"))
    }

    @Test
    fun `short names use norwegian brand strings`() {
        whenever(context.getString(R.string.operator_onecall)).thenReturn("OneCall")
        whenever(context.getString(R.string.operator_fjordkraft)).thenReturn("Fjordkraft Mobil")
        whenever(context.getString(R.string.operator_ice)).thenReturn("ice")
        assertEquals("OneCall", SimUtils.getShortOperatorName(context, "ONECALL", 0))
        assertEquals("Fjordkraft Mobil", SimUtils.getShortOperatorName(context, "Fjordkraft", 0))
        assertEquals("ice", SimUtils.getShortOperatorName(context, "Ice Norge", 0))
    }
}
