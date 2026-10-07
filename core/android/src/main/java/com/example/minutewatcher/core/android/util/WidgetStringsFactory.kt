package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.WidgetStrings
import com.example.minutewatcher.core.resources.R
import java.util.Locale

/**
 * Widget strings factory.
 * Creates WidgetStrings from localized context.
 */
object WidgetStringsFactory {
    fun create(context: Context): WidgetStrings {
        val appLocaleContext = AppLocaleContexts.forAppLocale(context)
        val effectiveLocale =
            appLocaleContext
                .resources
                .configuration
                .locales
                .get(0) ?: Locale.getDefault()

        return WidgetStrings(
            usedText = appLocaleContext.getString(R.string.used_text),
            minutesAbbr = appLocaleContext.getString(R.string.minutes_abbr),
            limitText = appLocaleContext.getString(R.string.limit_text),
            gbAbbr = appLocaleContext.getString(R.string.gb_abbr),
            refreshErrorText = appLocaleContext.getString(R.string.refresh_error),
            settingsLoadErrorText = appLocaleContext.getString(R.string.settings_load_error),
            noDataText = appLocaleContext.getString(R.string.no_data),
            noSimFoundText = appLocaleContext.getString(R.string.no_sim_found),
            staleDataWarningText = appLocaleContext.getString(R.string.stale_data_warning),
            simStatusSummaryFormat = appLocaleContext.getString(R.string.sim_status_summary),
            locale = effectiveLocale,
        )
    }

    fun createOperatorNames(
        context: Context,
        simSnapshots: List<SimSnapshot>,
    ): Map<Int, String> {
        val appLocaleContext = AppLocaleContexts.forAppLocale(context)
        return simSnapshots.associateBy(
            keySelector = { it.slot },
            valueTransform = { SimUtils.getShortOperatorName(appLocaleContext, it.operatorName, it.slot) },
        )
    }
}
