package com.github.djbigmuh.minutewatcher

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
class AboutAndBannerLocalizationTest {
    private lateinit var defaultContext: Context

    private val bannerLocales =
        listOf(
            "ar",
            "bn",
            "de",
            "es",
            "fr",
            "hi",
            "ja",
            "zh",
        )

    private val aboutLocales =
        listOf(
            "ar",
            "bn",
            "de",
            "es",
            "fr",
            "hi",
            "in",
            "ja",
            "pt",
            "pt-BR",
            "zh",
        )

    @Before
    fun setUp() {
        defaultContext = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `storage degraded banner string is translated and differs from default in target locales`() {
        bannerLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)
            val resId = R.string.tracking_banner_storage_degraded
            val defaultString = defaultContext.getString(resId)
            val localizedString = localizedContext.getString(resId)

            assertNotEquals(
                "String $resId for locale $localeTag should be translated and differ from default",
                defaultString,
                localizedString,
            )
        }
    }

    @Test
    fun `about section strings are translated and differ from default in target locales`() {
        aboutLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)

            val aboutKeys =
                listOf(
                    R.string.about_title,
                    R.string.about_message,
                    R.string.about_author_name,
                    R.string.about_signoff,
                    R.string.about_content_desc,
                )

            aboutKeys.forEach { resId ->
                val defaultString = defaultContext.getString(resId)
                val localizedString = localizedContext.getString(resId)

                assertNotEquals(
                    "String $resId for locale $localeTag should be translated and differ from default",
                    defaultString,
                    localizedString,
                )
            }
        }
    }

    @Test
    fun `about version format string formats correctly with arguments without raw placeholders`() {
        aboutLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)
            val formatted = localizedContext.getString(R.string.about_version_format, "1.0.0", 10)

            assertFalse(
                "Formatted string for locale $localeTag should not contain %1\$s",
                formatted.contains("%1\$s"),
            )
            assertFalse(
                "Formatted string for locale $localeTag should not contain %2\$d",
                formatted.contains("%2\$d"),
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
