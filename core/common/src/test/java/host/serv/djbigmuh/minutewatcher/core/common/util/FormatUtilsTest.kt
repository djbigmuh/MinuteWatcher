package host.serv.djbigmuh.minutewatcher.core.common.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class FormatUtilsTest {
    @Test
    fun `format traffic GB`() {
        assertEquals("1.5", FormatUtils.formatTrafficGb(Locale.US, 1.5f))
        assertEquals("0.0", FormatUtils.formatTrafficGb(Locale.US, 0f))
        assertEquals("1,5", FormatUtils.formatTrafficGb(Locale.GERMANY, 1.5f))
        assertEquals("-1,5", FormatUtils.formatTrafficGb(Locale.GERMANY, -1.5f))
    }

    @Test
    fun `format minutes display logic`() {
        assertEquals("100 min.", FormatUtils.formatMinutesDisplayLogic(Locale.US, 100, 500, false, "min."))
        assertEquals("100 / 500 min.", FormatUtils.formatMinutesDisplayLogic(Locale.US, 100, 500, true, "min."))
    }

    @Test
    fun `format minutes display logic with custom format`() {
        val result =
            FormatUtils.formatMinutesDisplayLogic(
                locale = Locale.US,
                remaining = 100,
                limit = 500,
                showLimit = true,
                unit = "min.",
                remainingWithLimitFormat = "%1\$s из %2\$s %3\$s",
            )
        assertEquals("100 из 500 min.", result)
    }

    @Test
    fun `format traffic display logic`() {
        assertEquals("1.5 GB", FormatUtils.formatTrafficDisplayLogic(Locale.US, 1.5f, 5.0f, false, "GB"))
        assertEquals("1.5 / 5.0 GB", FormatUtils.formatTrafficDisplayLogic(Locale.US, 1.5f, 5.0f, true, "GB"))
        assertEquals("1,5 / 5,0 GB", FormatUtils.formatTrafficDisplayLogic(Locale.GERMANY, 1.5f, 5.0f, true, "GB"))
    }

    @Test
    fun `format traffic display logic with custom format`() {
        val result =
            FormatUtils.formatTrafficDisplayLogic(
                locale = Locale.US,
                remainingGb = 1.5f,
                limitGb = 5.0f,
                showLimit = true,
                unit = "GB",
                remainingWithLimitFormat = "%1\$s of %2\$s %3\$s",
            )
        assertEquals("1.5 of 5.0 GB", result)
    }

    @Test
    fun `formatTrafficDisplay uses context locale and resources`() {
        val context = mock<Context>()
        val resources = mock<Resources>()
        val config = mock<Configuration>()
        val localeList = LocaleList(Locale.GERMANY)

        whenever(context.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(config)
        whenever(config.locales).thenReturn(localeList)
        whenever(context.getString(R.string.remaining_text)).thenReturn("Remaining:")
        whenever(context.getString(R.string.gb_abbr)).thenReturn("GB")
        whenever(context.getString(R.string.traffic_remaining_with_limit_format)).thenReturn("%1\$s / %2\$s %3\$s")
        whenever(context.getString(R.string.traffic_value_with_unit_format)).thenReturn("%1\$s %2\$s")

        val result = FormatUtils.formatTrafficDisplay(context, 1.5f, 5.0f, true)
        assertEquals("Remaining: 1,5 / 5,0 GB", result)
    }

    @Test
    fun `formatTrafficDisplay returns placeholder for null remaining`() {
        val context = mock<Context>()
        whenever(context.getString(R.string.value_placeholder)).thenReturn("—")

        val result = FormatUtils.formatTrafficDisplay(context, null, 5.0f, true)
        assertEquals("—", result)
        assertFalse(result.contains("-"))
    }

    @Test
    fun `formatMinutesDisplay returns placeholder when no limit and adjustment`() {
        val context = mock<Context>()
        whenever(context.getString(R.string.value_placeholder)).thenReturn("—")

        val result = FormatUtils.formatMinutesDisplay(context, 0, 0, 0, true)
        assertEquals("—", result)
        assertFalse(result.contains("-"))
    }

    @Test
    fun `formatMinutesDisplay adds space after remaining_text`() {
        val context = mock<Context>()
        whenever(context.getString(R.string.remaining_text)).thenReturn("Remaining:")
        whenever(context.getString(R.string.minutes_abbr)).thenReturn("min.")
        whenever(context.getString(R.string.traffic_remaining_with_limit_format)).thenReturn("%1\$s / %2\$s %3\$s")
        whenever(context.getString(R.string.traffic_value_with_unit_format)).thenReturn("%1\$s %2\$s")

        val result = FormatUtils.formatMinutesDisplay(context, 100, 500, 0, true)
        assertEquals("Remaining: 100 / 500 min.", result)
    }

    @Test
    fun `formatMbAsGb separates value and unit with space for US locale`() {
        val context = mock<Context>()
        val resources = mock<Resources>()
        val config = mock<Configuration>()
        val localeList = LocaleList(Locale.US)

        whenever(context.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(config)
        whenever(config.locales).thenReturn(localeList)
        whenever(context.getString(R.string.gb_abbr)).thenReturn("GB")

        val result = FormatUtils.formatMbAsGb(context, 1536f)
        assertEquals("1.5 GB", result)
    }

    @Test
    fun `formatMbAsGb separates value and unit with space for Germany locale`() {
        val context = mock<Context>()
        val resources = mock<Resources>()
        val config = mock<Configuration>()
        val localeList = LocaleList(Locale.GERMANY)

        whenever(context.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(config)
        whenever(config.locales).thenReturn(localeList)
        whenever(context.getString(R.string.gb_abbr)).thenReturn("GB")

        val result = FormatUtils.formatMbAsGb(context, 1536f)
        assertEquals("1,5 GB", result)
    }

    @Test
    fun `getLocale falls back to default on exception`() {
        val context = mock<Context>()
        val resources = mock<Resources>()
        whenever(context.resources).thenReturn(resources)
        whenever(resources.configuration).thenThrow(RuntimeException("Config error"))

        val locale = FormatUtils.getLocale(context)
        assertEquals(Locale.getDefault(), locale)
    }
}
