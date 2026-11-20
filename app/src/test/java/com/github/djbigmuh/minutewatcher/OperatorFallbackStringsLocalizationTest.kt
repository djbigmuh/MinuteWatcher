package com.github.djbigmuh.minutewatcher

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
class OperatorFallbackStringsLocalizationTest {
    private lateinit var defaultContext: Context

    private val supportedLocales =
        listOf(
            "ar",
            "bho",
            "bn",
            "de",
            "es",
            "fa",
            "fr",
            "gu",
            "ha",
            "hi",
            "id",
            "it",
            "ja",
            "jv",
            "ko",
            "mr",
            "pa",
            "pt-BR",
            "pt-PT",
            "ru",
            "ta",
            "te",
            "tr",
            "ur",
            "vi",
            "yo",
            "zh",
        )

    @Before
    fun setUp() {
        defaultContext = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `operator fallback strings format correctly without raw placeholders`() {
        supportedLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)

            val genericFormatted = localizedContext.getString(R.string.operator_generic, 1)
            assertTrue("String must not be empty", genericFormatted.isNotEmpty())
            assertFalse("Should not contain %1\$d", genericFormatted.contains("%1\$d"))
            assertFalse("Should not contain %1\$", genericFormatted.contains("%1\$"))

            val mccFormatted = localizedContext.getString(R.string.operator_with_mcc, "MCC:250 MNC:99")
            assertTrue("String must not be empty", mccFormatted.isNotEmpty())
            assertFalse("Should not contain %1\$s", mccFormatted.contains("%1\$s"))
            assertTrue("Should contain argument", mccFormatted.contains("MCC:250 MNC:99"))
        }
    }

    @Test
    fun `operator generic string is localized for specific locales`() {
        val specificLocales =
            listOf(
                "ru",
                "de",
                "ja",
                "zh",
                "ko",
                "ar",
                "es",
                "fr",
                "it",
                "pt-BR",
                "pt-PT",
                "tr",
                "vi",
                "fa",
                "ur",
                "hi",
                "bn",
                "ta",
                "te",
                "mr",
                "gu",
                "pa",
                "bho",
            )

        val defaultString = defaultContext.getString(R.string.operator_generic, 1)
        specificLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)
            val localizedString = localizedContext.getString(R.string.operator_generic, 1)
            assertNotEquals(
                "Operator generic for locale $localeTag should differ from default",
                defaultString,
                localizedString,
            )
        }
    }

    private fun getLocalizedContext(localeTag: String): Context {
        val locale = Locale.forLanguageTag(localeTag)
        val config = Configuration(defaultContext.resources.configuration)
        config.setLocale(locale)
        return defaultContext.createConfigurationContext(config)
    }
}
