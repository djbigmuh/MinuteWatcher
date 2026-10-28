package host.serv.djbigmuh.minutewatcher.core.common.util

import android.content.Context
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.resources.R
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

    /**
     * Formats MB amount as GB with unit (e.g., "1.5 GB" for US locale).
     *
     * @param context Context for string resources and locale configuration.
     * @param mb MB amount.
     * @return Formatted string with GB unit separated by a space.
     */
    fun formatMbAsGb(
        context: Context,
        mb: Float,
    ): String {
        val locale = getLocale(context)
        return "${formatTrafficGb(locale, mb / Constants.MB_PER_GB)} ${context.getString(R.string.gb_abbr)}"
    }

    fun formatTrafficDisplay(
        context: Context,
        remainingGb: Float?,
        limitGb: Float,
        showLimit: Boolean,
    ): String {
        if (remainingGb == null) return context.getString(R.string.value_placeholder)
        val gbUnit = context.getString(R.string.gb_abbr)
        val locale = getLocale(context)
        val remainingWithLimitFormat = context.getString(R.string.traffic_remaining_with_limit_format)
        val valueWithUnitFormat = context.getString(R.string.traffic_value_with_unit_format)
        val logic =
            formatTrafficDisplayLogic(
                locale = locale,
                remainingGb = remainingGb,
                limitGb = limitGb,
                showLimit = showLimit,
                unit = gbUnit,
                remainingWithLimitFormat = remainingWithLimitFormat,
                valueWithUnitFormat = valueWithUnitFormat,
            )
        return if (showLimit && limitGb > 0) {
            "${context.getString(R.string.remaining_text)} $logic"
        } else {
            logic
        }
    }

    fun formatTrafficDisplayLogic(
        locale: Locale,
        remainingGb: Float?,
        limitGb: Float,
        showLimit: Boolean,
        unit: String,
        remainingWithLimitFormat: String = "%1\$s / %2\$s %3\$s",
        valueWithUnitFormat: String = "%1\$s %2\$s",
    ): String =
        if (remainingGb == null) {
            ""
        } else if (showLimit && limitGb > 0) {
            String.format(
                locale,
                remainingWithLimitFormat,
                formatTrafficGb(locale, remainingGb),
                formatTrafficGb(locale, limitGb),
                unit,
            )
        } else {
            String.format(
                locale,
                valueWithUnitFormat,
                formatTrafficGb(locale, remainingGb),
                unit,
            )
        }

    fun formatMinutesDisplay(
        context: Context,
        remaining: Int,
        limit: Int,
        manualAdjustment: Int,
        showLimit: Boolean,
    ): String {
        if (limit == 0 && manualAdjustment == 0) return context.getString(R.string.value_placeholder)
        val minUnit = context.getString(R.string.minutes_abbr)
        val remainingWithLimitFormat = context.getString(R.string.traffic_remaining_with_limit_format)
        val valueWithUnitFormat = context.getString(R.string.traffic_value_with_unit_format)
        val locale = getLocale(context)
        val logic =
            formatMinutesDisplayLogic(
                locale = locale,
                remaining = remaining,
                limit = limit,
                showLimit = showLimit,
                unit = minUnit,
                remainingWithLimitFormat = remainingWithLimitFormat,
                valueWithUnitFormat = valueWithUnitFormat,
            )
        return if (showLimit && limit > 0) {
            "${context.getString(R.string.remaining_text)} $logic"
        } else {
            logic
        }
    }

    fun formatMinutesDisplayLogic(
        locale: Locale,
        remaining: Int,
        limit: Int,
        showLimit: Boolean,
        unit: String,
        remainingWithLimitFormat: String = "%1\$s / %2\$s %3\$s",
        valueWithUnitFormat: String = "%1\$s %2\$s",
    ): String =
        if (showLimit && limit > 0) {
            String.format(locale, remainingWithLimitFormat, remaining, limit, unit)
        } else {
            String.format(locale, valueWithUnitFormat, remaining, unit)
        }

    fun formatMinutesPlural(
        context: Context,
        minutes: Int,
    ): String = context.resources.getQuantityString(R.plurals.minutes_plurals, minutes, minutes)

    fun formatDaysPlural(
        context: Context,
        days: Int,
    ): String = context.resources.getQuantityString(R.plurals.days_plurals, days, days)

    fun getLocale(context: Context): Locale =
        try {
            context.resources
                .configuration
                .locales
                .get(0)
        } catch (e: Exception) {
            AppLogger.w("FormatUtils: Failed to get locale from context, using default", e)
            Locale.getDefault()
        }
}
