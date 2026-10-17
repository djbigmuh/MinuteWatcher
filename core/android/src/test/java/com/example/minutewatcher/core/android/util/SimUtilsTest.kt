package com.example.minutewatcher.core.android.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.content.res.AppCompatResources
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class SimUtilsTest {
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
    fun `getOperatorIconResource returns correct icons for known tokens`() {
        assertEquals(R.drawable.ic_operator_mts, SimUtils.getOperatorIconResource("MTS RUS"))
        assertEquals(R.drawable.ic_operator_beeline, SimUtils.getOperatorIconResource("Beeline"))
        assertEquals(R.drawable.ic_operator_megafon, SimUtils.getOperatorIconResource("MegaFon Corp"))
        assertEquals(R.drawable.ic_operator_tele2, SimUtils.getOperatorIconResource("Tele2 Russia"))
        assertEquals(R.drawable.ic_operator_tele2, SimUtils.getOperatorIconResource("T2"))
        assertEquals(R.drawable.ic_operator_mgts, SimUtils.getOperatorIconResource("MGTS Mobile"))
        assertEquals(R.drawable.ic_operator_yota, SimUtils.getOperatorIconResource("Yota LTE"))
        assertEquals(R.drawable.ic_operator_movistar, SimUtils.getOperatorIconResource("Movistar"))
        assertEquals(R.drawable.ic_unknown, SimUtils.getOperatorIconResource("Unknown"))
    }

    @Test
    fun `getOperatorIconResource returns correct icons for cyrillic brand names`() {
        assertEquals(R.drawable.ic_operator_mts, SimUtils.getOperatorIconResource("МТС"))
        assertEquals(R.drawable.ic_operator_megafon, SimUtils.getOperatorIconResource("МегаФон"))
        assertEquals(R.drawable.ic_operator_beeline, SimUtils.getOperatorIconResource("Билайн"))
        assertEquals(R.drawable.ic_operator_tele2, SimUtils.getOperatorIconResource("Теле2"))
        assertEquals(R.drawable.ic_operator_tele2, SimUtils.getOperatorIconResource("Т2"))
        assertEquals(R.drawable.ic_operator_mgts, SimUtils.getOperatorIconResource("МГТС"))
        assertEquals(R.drawable.ic_operator_yota, SimUtils.getOperatorIconResource("Йота"))
        assertEquals(R.drawable.ic_operator_mts, SimUtils.getOperatorIconResource("МТС Россия"))
        assertEquals(R.drawable.ic_operator_mts, SimUtils.getOperatorIconResource("MTS МТС"))
        assertEquals(R.drawable.ic_unknown, SimUtils.getOperatorIconResource("СберМобайл"))
    }

    @Test
    fun `getOperatorIconResource is case insensitive`() {
        assertEquals(R.drawable.ic_operator_mts, SimUtils.getOperatorIconResource("mts"))
    }

    @Test
    fun `getOperatorIconResource handles delimiters correctly`() {
        assertEquals(R.drawable.ic_operator_mts, SimUtils.getOperatorIconResource("MTS-RUS"))
        assertEquals(R.drawable.ic_operator_mts, SimUtils.getOperatorIconResource("MTS_RUS"))
    }

    @Test
    fun `operatorIconMap covers all operatorResourceMap keys`() {
        val missing = OperatorResolver.operatorResourceMap.values.filter { it !in SimUtils.operatorIconMap }
        assertTrue("operatorIconMap missing keys for operatorResourceMap: $missing", missing.isEmpty())
    }

    @Test
    fun `all operator icons load successfully in Robolectric`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        SimUtils.operatorIconMap.values.toSet().forEach { drawableResId ->
            val drawable = AppCompatResources.getDrawable(appContext, drawableResId)
            assertTrue("Drawable $drawableResId must load successfully", drawable != null)
        }
    }

    @Test
    fun `no operator icon has autoMirrored true`() {
        val resDir = findResDir()
        val drawableDir = File(resDir, "drawable")
        assertTrue("Drawable dir must exist at $drawableDir", drawableDir.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        drawableDir
            .listFiles { file ->
                file.name.startsWith(
                    "ic_operator_",
                ) &&
                    file.extension == "xml"
            }?.forEach { file ->
                val doc = builder.parse(file)
                val root = doc.documentElement
                val autoMirrored = root.getAttribute("android:autoMirrored")
                assertFalse("Icon ${file.name} must not have android:autoMirrored=\"true\"", autoMirrored == "true")
            }
    }

    private fun findResDir(): File {
        val candidates =
            listOf(
                File("core/resources/src/main/res"),
                File("../core/resources/src/main/res"),
                File("../../core/resources/src/main/res"),
                File("C:/MinuteWatcher/core/resources/src/main/res"),
            )
        return candidates.firstOrNull { it.exists() } ?: File("core/resources/src/main/res")
    }

    @Test
    fun `isInactiveSim returns true for empty or null and false for non empty strings`() {
        assertTrue(SimUtils.isInactiveSim(null))
        assertTrue(SimUtils.isInactiveSim(""))
        assertFalse(SimUtils.isInactiveSim("MTS"))
        assertFalse(SimUtils.isInactiveSim("null"))
        assertFalse(SimUtils.isInactiveSim("NULL"))
    }

    @Test
    fun `getShortOperatorName returns full long names without truncation`() {
        val longName = "This is a very long operator name that should not be truncated"
        val result = SimUtils.getShortOperatorName(context, longName, 0)

        assertEquals(longName, result)
        assertFalse(result.contains("..."))
    }

    @Test
    fun `getShortOperatorName returns generic operator name for empty operator name and slot 0`() {
        whenever(context.getString(R.string.operator_generic, 1)).thenReturn("Operator 1")
        val result = SimUtils.getShortOperatorName(context, "", 0)
        assertEquals("Operator 1", result)
    }

    @Test
    fun `getShortOperatorName returns generic operator name for empty operator name and slot 2`() {
        whenever(context.getString(R.string.operator_generic, 3)).thenReturn("Operator 3")
        val result = SimUtils.getShortOperatorName(context, "", 2)
        assertEquals("Operator 3", result)
    }

    @Test
    fun `getShortOperatorName for empty operator name does not return no_sim_found`() {
        whenever(context.getString(R.string.operator_generic, 1)).thenReturn("Operator 1")
        whenever(context.getString(R.string.no_sim_found)).thenReturn("No SIM found")
        val result = SimUtils.getShortOperatorName(context, "", 0)
        assertNotEquals("No SIM found", result)
    }

    @Test
    fun `getShortOperatorName returns resource string for known brand operator name`() {
        whenever(context.getString(R.string.operator_mts)).thenReturn("MTS")
        val result = SimUtils.getShortOperatorName(context, "MTS RUS", 0)
        assertEquals("MTS", result)
    }

    @Test
    fun `getShortOperatorName returns resource string for cyrillic brand operator name`() {
        whenever(context.getString(R.string.operator_mts)).thenReturn("МТС")
        val result = SimUtils.getShortOperatorName(context, "МТС", 0)
        assertEquals("МТС", result)
    }

    @Test
    fun `getShortOperatorName returns original full name on resource exception when original name is non-empty`() {
        whenever(context.getString(R.string.operator_mts)).thenThrow(RuntimeException("Resource error"))
        val result = SimUtils.getShortOperatorName(context, "MTS RUS", 0)
        assertEquals("MTS RUS", result)
    }

    @Test
    fun `getShortOperatorName preserves case on resource exception for mixed case operator name`() {
        whenever(context.getString(R.string.operator_mts)).thenThrow(RuntimeException("Resource error"))
        val result = SimUtils.getShortOperatorName(context, "mts rus", 0)
        assertEquals("mts rus", result)
    }

    @Test
    fun `getShortOperatorName returns full name on resource exception for long operator names`() {
        whenever(context.getString(R.string.operator_mts)).thenThrow(RuntimeException("Resource error"))
        val longName = "MTS Russia Telecommunications Network"
        val result = SimUtils.getShortOperatorName(context, longName, 0)
        assertEquals("MTS Russia Telecommunications Network", result)
        assertFalse(result.contains("..."))
    }

    @Test
    fun `getShortOperatorName returns generic operator name on exception when original name is empty`() {
        whenever(context.getString(eq(R.string.operator_generic), eq(1))).thenReturn("Operator 1")
        whenever(context.getString(eq(R.string.operator_generic), any())).thenAnswer {
            val arg1 = it.getArgument<Int>(1)
            "Operator $arg1"
        }
        val result = SimUtils.getShortOperatorName(context, "", 0)
        assertEquals("Operator 1", result)
    }

    @Test
    fun `getOperatorResourceId handles aliases from consolidated map`() {
        val resId = OperatorResolver.getOperatorResourceId("T2")
        assertEquals(R.string.operator_tele2, resId)
    }

    @Test
    fun `getOperatorResourceId works for newly added operators`() {
        val resId = OperatorResolver.getOperatorResourceId("Rostelecom")
        assertEquals(R.string.operator_rostelecom, resId)
    }

    @Test
    fun `operatorIconMap values follow ic_operator schema and are distinct except vodafone_it`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val seenDrawables = mutableMapOf<Int, Int>()

        SimUtils.operatorIconMap.forEach { (_, drawableResId) ->
            val resName = appContext.resources.getResourceEntryName(drawableResId)
            assertTrue(
                "Drawable name $resName must start with ic_operator_ or be ic_unknown",
                resName.startsWith("ic_operator_") || resName == "ic_unknown",
            )
            seenDrawables[drawableResId] = (seenDrawables[drawableResId] ?: 0) + 1
        }

        val vodafoneDrawable = R.drawable.ic_operator_vodafone
        val unknownDrawable = R.drawable.ic_unknown
        seenDrawables.forEach { (drawableResId, count) ->
            if (drawableResId == vodafoneDrawable) {
                assertEquals(
                    "ic_operator_vodafone should be shared by exactly 1 key (operator_vodafone)",
                    1,
                    count,
                )
            } else if (drawableResId == unknownDrawable) {
                // Neutral icon can be shared
            } else {
                assertEquals("Drawable $drawableResId should be unique", 1, count)
            }
        }
    }

    @Test
    fun `ic_movistar legacy drawable is absent from resources`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val movistarId = appContext.resources.getIdentifier("ic_movistar", "drawable", appContext.packageName)
        assertEquals("ic_movistar must not exist in resources", 0, movistarId)
    }
}
