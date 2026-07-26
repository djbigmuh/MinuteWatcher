package com.example.minutewatcher.core.model.util
import android.content.Context
import androidx.core.content.ContextCompat
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.Constants

/**
 * Централизованный выбор цветов (Этап 4, п. 20).
 */
object ColorProvider {
    enum class UsageStatus {
        NORMAL, LOW, CRITICAL
    }

    fun getUsageStatus(remaining: Float, limit: Float): UsageStatus {
        return when {
            limit <= 0 -> UsageStatus.NORMAL
            remaining <= 0 -> UsageStatus.CRITICAL
            remaining < limit * Constants.CRITICAL_REMAINING_THRESHOLD_PERCENT -> UsageStatus.CRITICAL
            remaining < limit * Constants.LOW_REMAINING_THRESHOLD_PERCENT -> UsageStatus.LOW
            else -> UsageStatus.NORMAL
        }
    }

    fun getUsageColor(context: Context, remaining: Float, limit: Float): Int {
        return when (getUsageStatus(remaining, limit)) {
            UsageStatus.CRITICAL -> ContextCompat.getColor(context, R.color.red)
            UsageStatus.LOW -> ContextCompat.getColor(context, R.color.orange)
            UsageStatus.NORMAL -> ContextCompat.getColor(context, R.color.green)
        }
    }
}
