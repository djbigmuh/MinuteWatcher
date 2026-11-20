package com.github.djbigmuh.minutewatcher

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.common.util.FormatUtils
import com.github.djbigmuh.minutewatcher.core.resources.R
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
class NotificationStringLocalizationTest {
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
    fun `notification channel names and descriptions are non-empty for all supported locales`() {
        supportedLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)

            val channelName = localizedContext.getString(R.string.notification_channel_name)
            val channelDesc = localizedContext.getString(R.string.notification_channel_desc)
            val aodName = localizedContext.getString(R.string.notification_channel_aod_summary_name)
            val aodDesc = localizedContext.getString(R.string.notification_channel_aod_summary_desc)

            assertTrue("Channel name for $localeTag must not be empty", channelName.isNotEmpty())
            assertTrue("Channel desc for $localeTag must not be empty", channelDesc.isNotEmpty())
            assertTrue("AOD channel name for $localeTag must not be empty", aodName.isNotEmpty())
            assertTrue("AOD channel desc for $localeTag must not be empty", aodDesc.isNotEmpty())
        }
    }

    @Test
    fun `notification titles and bodies format correctly without unparsed placeholders`() {
        supportedLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)

            val minTitle = localizedContext.getString(R.string.notification_minutes_title, "MTS")
            assertFalse("Title for $localeTag should format operator", minTitle.contains("%1\$s"))

            val trafficTitle = localizedContext.getString(R.string.notification_traffic_title, "MTS")
            assertFalse("Traffic title for $localeTag should format operator", trafficTitle.contains("%1\$s"))

            val daysText = FormatUtils.formatDaysPlural(localizedContext, 5)
            val minText = FormatUtils.formatMinutesPlural(localizedContext, 30)
            val level = localizedContext.getString(R.string.usage_level_low)

            val minBody =
                localizedContext.getString(
                    R.string.notification_minutes_body,
                    level,
                    minText,
                    15,
                    daysText,
                )
            assertFalse("Minutes body for $localeTag should format arguments", minBody.contains("%1\$s"))

            val minBodyNoPeriod =
                localizedContext.getString(
                    R.string.notification_minutes_body_no_period,
                    level,
                    minText,
                    15,
                )
            assertFalse(
                "Minutes body no period for $localeTag should format arguments",
                minBodyNoPeriod.contains("%1\$s"),
            )

            val minExhausted = localizedContext.getString(R.string.notification_minutes_exhausted_body)
            assertTrue("Minutes exhausted body for $localeTag must not be empty", minExhausted.isNotEmpty())

            val trafficExhausted = localizedContext.getString(R.string.notification_traffic_exhausted_body)
            assertTrue("Traffic exhausted body for $localeTag must not be empty", trafficExhausted.isNotEmpty())
        }
    }

    @Test
    fun `plural formatting returns localized non-empty strings across all supported locales`() {
        supportedLocales.forEach { localeTag ->
            val localizedContext = getLocalizedContext(localeTag)

            val minutes1 = FormatUtils.formatMinutesPlural(localizedContext, 1)
            val minutes5 = FormatUtils.formatMinutesPlural(localizedContext, 5)
            val days1 = FormatUtils.formatDaysPlural(localizedContext, 1)
            val days5 = FormatUtils.formatDaysPlural(localizedContext, 5)

            assertTrue("Minutes plural (1) for $localeTag must not be empty", minutes1.isNotEmpty())
            assertTrue("Minutes plural (5) for $localeTag must not be empty", minutes5.isNotEmpty())
            assertTrue("Days plural (1) for $localeTag must not be empty", days1.isNotEmpty())
            assertTrue("Days plural (5) for $localeTag must not be empty", days5.isNotEmpty())
        }
    }

    private fun getLocalizedContext(localeTag: String): Context {
        val locale = Locale.forLanguageTag(localeTag)
        val config = Configuration(defaultContext.resources.configuration)
        config.setLocale(locale)
        return defaultContext.createConfigurationContext(config)
    }
}
