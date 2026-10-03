package com.example.minutewatcher.core.common.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
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
        assertEquals("100 min.", FormatUtils.formatMinutesDisplayLogic(100, 500, false, "min."))
        assertEquals("100 / 500 min.", FormatUtils.formatMinutesDisplayLogic(100, 500, true, "min."))
    }

    @Test
    fun `format traffic display logic`() {
        assertEquals("1.5 GB", FormatUtils.formatTrafficDisplayLogic(Locale.US, 1.5f, 5.0f, false, "GB"))
        assertEquals("1.5 / 5.0 GB", FormatUtils.formatTrafficDisplayLogic(Locale.US, 1.5f, 5.0f, true, "GB"))
        assertEquals("1,5 / 5,0 GB", FormatUtils.formatTrafficDisplayLogic(Locale.GERMANY, 1.5f, 5.0f, true, "GB"))
    }

    @Test
    fun `formatTrafficDisplay uses context locale`() {
        val context = mock<Context>()
        val resources = mock<Resources>()
        val config = mock<Configuration>()
        val localeList = LocaleList(Locale.GERMANY)

        whenever(context.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(config)
        whenever(config.locales).thenReturn(localeList)
        whenever(context.getString(R.string.remaining_text)).thenReturn("Remaining:")
        whenever(context.getString(R.string.gb_abbr)).thenReturn("GB")

        val result = FormatUtils.formatTrafficDisplay(context, 1.5f, 5.0f, true)
        assertEquals("Remaining: 1,5 / 5,0 GB", result)
    }

    @Test
    fun `formatMinutesDisplay adds space after remaining_text`() {
        val context = mock<Context>()
        whenever(context.getString(R.string.remaining_text)).thenReturn("Remaining:")
        whenever(context.getString(R.string.minutes_abbr)).thenReturn("min.")

        val result = FormatUtils.formatMinutesDisplay(context, 100, 500, 0, true)
        assertEquals("Remaining: 100 / 500 min.", result)
    }
}
