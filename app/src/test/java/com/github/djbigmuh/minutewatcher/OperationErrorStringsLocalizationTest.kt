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
class OperationErrorStringsLocalizationTest {
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
    fun `operation error strings are localized and differ from default English strings`() {
        supportedLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)

            val errorKeys =
                listOf(
                    R.string.error_invalid_sim_slot,
                    R.string.error_storage_unavailable,
                    R.string.error_sim_removed,
                    R.string.error_operation_failed_retry,
                    R.string.error_unexpected,
                )

            errorKeys.forEach { resId ->
                val defaultString = defaultContext.getString(resId)
                val localizedString = localizedContext.getString(resId)
                assertNotEquals(
                    "String for $resId in locale $localeTag should be translated and differ from default",
                    defaultString,
                    localizedString,
                )
            }
        }
    }

    @Test
    fun `operation error strings format correctly with arguments without leaving raw placeholders`() {
        supportedLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)

            val slotFormatted = localizedContext.getString(R.string.error_invalid_sim_slot, 1)
            assertFalse(
                "Formatted string for locale $localeTag should not contain %1\$d",
                slotFormatted.contains("%1\$d"),
            )

            val storageFormatted = localizedContext.getString(R.string.error_storage_unavailable, 1)
            assertFalse(
                "Formatted string for locale $localeTag should not contain %1\$d",
                storageFormatted.contains("%1\$d"),
            )

            val removedFormatted = localizedContext.getString(R.string.error_sim_removed, 1)
            assertFalse(
                "Formatted string for locale $localeTag should not contain %1\$d",
                removedFormatted.contains("%1\$d"),
            )
        }
    }

    @Test
    fun `error operation failed retry and error unexpected resolve without arguments and are not empty`() {
        supportedLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)
            val retryMessage =
                localizedContext.getString(R.string.error_operation_failed_retry)
            assertTrue(
                "Operation failed retry string for locale $localeTag must not be empty",
                retryMessage.isNotEmpty(),
            )

            val unexpectedMessage =
                localizedContext.getString(R.string.error_unexpected)
            assertTrue(
                "Unexpected error string for locale $localeTag must not be empty",
                unexpectedMessage.isNotEmpty(),
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
