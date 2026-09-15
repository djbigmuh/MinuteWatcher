package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.WidgetStrings
import com.example.minutewatcher.core.resources.R

/**
 * Widget strings factory.
 * Creates WidgetStrings from context.
 * Recreated on configuration change (locale).
 *
 * API compliance: on AOSP, locale change via
 * AppCompatDelegate.setApplicationLocales() triggers configuration
 * recreation. On custom firmwares where this doesn't happen,
 * strings will update on the next Activity recreation —
 * это проблема кастомной прошивки.
 */
object WidgetStringsFactory {
    fun create(context: Context): WidgetStrings =
        WidgetStrings(
            usedText = context.getString(R.string.used_text),
            minutesAbbr = context.getString(R.string.minutes_abbr),
            limitText = context.getString(R.string.limit_text),
            gbAbbr = context.getString(R.string.gb_abbr),
            fractionFormat = context.getString(R.string.fraction_format),
        )

    fun createOperatorNames(
        context: Context,
        simSnapshots: List<SimSnapshot>,
    ): Map<Int, String> =
        simSnapshots.associateBy(
            keySelector = { it.slot },
            valueTransform = { SimUtils.getShortOperatorName(context, it.operatorName) },
        )
}
