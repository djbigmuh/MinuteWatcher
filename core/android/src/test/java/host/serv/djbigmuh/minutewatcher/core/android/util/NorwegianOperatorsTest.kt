package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.resources.R
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
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        whenever(context.assets).thenReturn(appContext.assets)
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
        assertEquals("operator_telia", json.getString("24207"))
    }

    @Test
    fun `norwegian brand tokens resolve to brand string resources`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(R.string.operator_onecall, OperatorResolver.getOperatorResourceId("OneCall") as Int)
        assertEquals(R.string.operator_mycall, OperatorResolver.getOperatorResourceId("MYCALL") as Int)
        assertEquals(R.string.operator_phonero, OperatorResolver.getOperatorResourceId("Phonero") as Int)
        assertEquals(R.string.operator_talkmore, OperatorResolver.getOperatorResourceId("Talkmore") as Int)
        assertEquals(R.string.operator_chilimobil, OperatorResolver.getOperatorResourceId("chilimobil") as Int)
        assertEquals(R.string.operator_fjordkraft, OperatorResolver.getOperatorResourceId("Fjordkraft Mobil") as Int)
        assertEquals(R.string.operator_telenor, OperatorResolver.getOperatorResourceId("Telenor") as Int)
        assertEquals(R.string.operator_telia, OperatorResolver.getOperatorResourceId("Telia Norge") as Int)
        assertEquals("operator_ice", OperatorResolver.resolve(appContext, "", listOf("24206"), 0).brandKey)
    }

    @Test
    fun `norwegian mvno brands resolve to their own icons`() {
        assertEquals(R.drawable.ic_operator_onecall, SimUtils.getOperatorIconResource("OneCall"))
        assertEquals(R.drawable.ic_operator_mycall, SimUtils.getOperatorIconResource("MyCall"))
        assertEquals(R.drawable.ic_operator_phonero, SimUtils.getOperatorIconResource("Phonero"))
        assertEquals(R.drawable.ic_operator_talkmore, SimUtils.getOperatorIconResource("Talkmore"))
        assertEquals(R.drawable.ic_operator_chilimobil, SimUtils.getOperatorIconResource("Chilimobil"))
        assertEquals(R.drawable.ic_operator_fjordkraft, SimUtils.getOperatorIconResource("Fjordkraft Mobil"))
    }

    @Test
    fun `norwegian network brands keep their icons`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(R.drawable.ic_operator_telenor, SimUtils.getOperatorIconResource("Telenor"))
        assertEquals(R.drawable.ic_operator_telia, SimUtils.getOperatorIconResource("Telia Norge"))
        val iceResult = OperatorResolver.resolve(appContext, "", listOf("24206"), 0)
        assertEquals("operator_ice", iceResult.brandKey)
        assertEquals(R.drawable.ic_operator_ice, SimUtils.getOperatorIconResource(iceResult.brandKey))
    }

    @Test
    fun `short names preserve reported operator name`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ONECALL", SimUtils.getShortOperatorName(context, "ONECALL", 0))
        assertEquals("Fjordkraft", SimUtils.getShortOperatorName(context, "Fjordkraft", 0))
        assertEquals("ice", OperatorResolver.resolve(appContext, "", listOf("24206"), 0).displayName)
    }
}
