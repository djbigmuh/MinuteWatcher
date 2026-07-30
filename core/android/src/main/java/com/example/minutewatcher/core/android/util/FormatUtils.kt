package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.resources.R
import java.util.Locale

/**
 * Централизованное форматирование строк.
 */
object FormatUtils {
    fun formatTrafficGb(gb: Float): String = "%.1f".format(Locale.getDefault(), gb)

    fun formatTrafficMb(
        context: Context,
        mb: Float,
    ): String = "${formatTrafficGb(mb / Constants.MB_PER_GB)}${context.getString(R.string.gb_abbr)}"

    fun formatTrafficMb(
        context: Context,
        mb: Int,
    ): String = formatTrafficMb(context, mb.toFloat())

    fun formatTrafficDisplay(
        context: Context,
        remainingGb: Float,
        limitGb: Float,
        showLimit: Boolean,
    ): String {
        if (remainingGb < 0) return "-"
        val gbUnit = context.getString(R.string.gb_abbr)
        val logic = formatTrafficDisplayLogic(remainingGb, limitGb, showLimit, gbUnit)
        return if (showLimit && limitGb > 0) {
            "${context.getString(R.string.remaining_text)}$logic"
        } else {
            logic
        }
    }

    fun formatTrafficDisplayLogic(
        remainingGb: Float,
        limitGb: Float,
        showLimit: Boolean,
        unit: String,
    ): String {
        val rem = "${formatTrafficGb(remainingGb)}$unit"
        return if (showLimit && limitGb > 0) {
            "$rem / ${formatTrafficGb(limitGb)}$unit"
        } else {
            rem
        }
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
            "${context.getString(R.string.remaining_text)}$logic"
        } else {
            logic
        }
    }

    fun formatMinutesDisplayLogic(
        remaining: Int,
        limit: Int,
        showLimit: Boolean,
        unit: String,
    ): String {
        val rem = "$remaining$unit"
        return if (showLimit && limit > 0) {
            "$rem / $limit$unit"
        } else {
            rem
        }
    }

    fun formatMinutesPlural(
        context: Context,
        minutes: Int,
    ): String = context.resources.getQuantityString(R.plurals.minutes_plurals, minutes, minutes)

    fun formatDaysPlural(
        context: Context,
        days: Int,
    ): String = context.resources.getQuantityString(R.plurals.days_plurals, days, days)

    fun formatMinutes(min: Int): String = min.toString()
}


