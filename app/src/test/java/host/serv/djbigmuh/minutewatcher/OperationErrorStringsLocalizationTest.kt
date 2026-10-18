package host.serv.djbigmuh.minutewatcher

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
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
                    R.string.error_unknown,
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

            val unknownFormatted = localizedContext.getString(R.string.error_unknown, "TestError")
            assertFalse(
                "Formatted string for locale $localeTag should not contain %1\$s",
                unknownFormatted.contains("%1\$s"),
            )
        }
    }

    @Test
    fun `error operation failed retry resolves without arguments and is not empty`() {
        supportedLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)
            val message = localizedContext.getString(R.string.error_operation_failed_retry)
            assertTrue("Operation failed retry string for locale $localeTag must not be empty", message.isNotEmpty())
        }
    }

    private fun getLocalizedContext(localeTag: String): Context {
        val locale = Locale.forLanguageTag(localeTag)
        val config = Configuration(defaultContext.resources.configuration)
        config.setLocale(locale)
        return defaultContext.createConfigurationContext(config)
    }
}
