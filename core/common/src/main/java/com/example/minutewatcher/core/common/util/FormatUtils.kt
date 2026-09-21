package com.example.minutewatcher.core.common.util

import android.content.Context
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.resources.R
import java.util.Locale

/**
 * Centralized string formatting.
 */
object FormatUtils {
    /**
     * Formats traffic amount in GB with one decimal place using the provided locale.
     *
     * @param locale Effective locale for decimal separator.
     * @param gb GB amount.
     * @return Formatted string (e.g., "1.5" or "1,5").
     */
    fun formatTrafficGb(
        locale: Locale,
        gb: Float,
    ): String = "%.1f".format(locale, gb)

    fun formatMbAsGb(
        context: Context,
        mb: Float,
    ): String {
        val locale = getLocaleFromContext(context)
        return "${formatTrafficGb(locale, mb / Constants.MB_PER_GB)}${context.getString(R.string.gb_abbr)}"
    }

    fun formatTrafficDisplay(
        context: Context,
        remainingGb: Float,
        limitGb: Float,
        showLimit: Boolean,
    ): String {
        if (remainingGb < 0) return "-"
        val gbUnit = context.getString(R.string.gb_abbr)
        val locale = getLocaleFromContext(context)
        val logic = formatTrafficDisplayLogic(locale, remainingGb, limitGb, showLimit, gbUnit)
        return if (showLimit && limitGb > 0) {
            "${context.getString(R.string.remaining_text)} $logic"
        } else {
            logic
        }
    }

    fun formatTrafficDisplayLogic(
        locale: Locale,
        remainingGb: Float,
        limitGb: Float,
        showLimit: Boolean,
        unit: String,
    ): String =
        if (showLimit && limitGb > 0) {
            "${formatTrafficGb(locale, remainingGb)} / ${formatTrafficGb(locale, limitGb)} $unit"
        } else {
            "${formatTrafficGb(locale, remainingGb)} $unit"
        }

    fun formatMinutesDisplay(
        context: Context,
        remaining: Int,
        limit: Int,
        manualAdjustment: Int,
        showLimit: Boolean,
    ): String {
        if (limit == 0 && manualAdjustment == 0) return "-"
        val minUnit = context.getString(R.string.minutes_abbr)
        val logic = formatMinutesDisplayLogic(remaining, limit, showLimit, minUnit)
        return if (showLimit && limit > 0) {
            "${context.getString(R.string.remaining_text)} $logic"
        } else {
            logic
        }
    }

    fun formatMinutesDisplayLogic(
        remaining: Int,
        limit: Int,
        showLimit: Boolean,
        unit: String,
    ): String =
        if (showLimit && limit > 0) {
            "$remaining / $limit $unit"
        } else {
            "$remaining $unit"
        }

    fun formatMinutesPlural(
        context: Context,
        minutes: Int,
    ): String = context.resources.getQuantityString(R.plurals.minutes_plurals, minutes, minutes)

    fun formatDaysPlural(
        context: Context,
        days: Int,
    ): String = context.resources.getQuantityString(R.plurals.days_plurals, days, days)

    private fun getLocaleFromContext(context: Context): Locale =
        try {
            context.resources
                .configuration
                .locales
                .get(0) ?: Locale.getDefault()
        } catch (e: Exception) {
            AppLogger.w("FormatUtils: Failed to get locale from context, using default", e)
            Locale.getDefault()
        }
}
