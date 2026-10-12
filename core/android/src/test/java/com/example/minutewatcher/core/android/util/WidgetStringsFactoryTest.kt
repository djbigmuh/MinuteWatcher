package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class WidgetStringsFactoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    @Config(qualifiers = "de")
    fun `create uses configuration locale when app locales are empty`() {
        val strings = WidgetStringsFactory.create(context)
        assertEquals(Locale.GERMAN.language, strings.locale.language)
    }

    @Test
    @Config(qualifiers = "fr")
    fun `create uses configuration locale for french qualifier when app locales are empty`() {
        val strings = WidgetStringsFactory.create(context)
        assertEquals(Locale.FRENCH.language, strings.locale.language)
    }

    @Test
    fun `create initializes formatting resources correctly`() {
        val strings = WidgetStringsFactory.create(context)
        assertEquals("—", strings.valuePlaceholder)
        assertEquals("%1\$s: %2\$s", strings.labelValueFormat)
        assertEquals("/%1\$s", strings.limitSuffixFormat)
    }
}
