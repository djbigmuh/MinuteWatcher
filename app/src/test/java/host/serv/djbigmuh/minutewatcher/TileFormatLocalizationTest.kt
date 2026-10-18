package host.serv.djbigmuh.minutewatcher

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
class TileFormatLocalizationTest {
    private lateinit var defaultContext: Context
    private val supportedLocales =
        listOf(
            "en",
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
    fun `tile_operator_value_format formats correctly across all supported locales without residual placeholders`() {
        supportedLocales.forEach { localeTag ->
            val context = if (localeTag == "en") defaultContext else getLocalizedContext(localeTag)
            val formatted = context.getString(R.string.tile_operator_value_format, "Operator", "100 min")
            assertFalse("Formatted string for locale $localeTag should not contain %1\$s", formatted.contains("%1\$s"))
            assertFalse("Formatted string for locale $localeTag should not contain %2\$s", formatted.contains("%2\$s"))
            assertTrue(
                "Formatted string for locale $localeTag must contain operator name",
                formatted.contains("Operator"),
            )
            assertTrue("Formatted string for locale $localeTag must contain value", formatted.contains("100 min"))
        }
    }

    private fun getLocalizedContext(localeTag: String): Context {
        val locale = Locale.forLanguageTag(localeTag)
        val config = Configuration(defaultContext.resources.configuration)
        config.setLocale(locale)
        return defaultContext.createConfigurationContext(config)
    }
}
