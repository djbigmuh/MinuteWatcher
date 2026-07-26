package com.example.minutewatcher.core.model
import android.graphics.Color

object Constants {
    const val PREFS_NAME = "app_prefs"

    object Notifications {
        const val CHANNEL_ID = "usage_monitor_channel"
    }

    object UI {
        const val DEFAULT_BG_COLOR: Int = Color.BLACK
    }

    object Widget {
        const val FONT_SIZE_SMALL = 12f
        const val FONT_SIZE_MEDIUM = 14f

        const val DEBOUNCE_MS = 300L
    }

    const val BYTES_PER_MB = 1024L * 1024L
    const val MB_PER_GB = 1024.0f
    const val LOW_REMAINING_THRESHOLD_PERCENT = 0.20f
    const val CRITICAL_REMAINING_THRESHOLD_PERCENT = 0.10f
    const val MAX_MINUTES_LIMIT = 44640
    const val MAX_TRAFFIC_LIMIT_GB = 10240f
}

object SimConstants {
    const val MAX_SUPPORTED_SIM_SLOTS = 2
    val SUPPORTED_SLOT_INDICES = 0 until MAX_SUPPORTED_SIM_SLOTS
}
