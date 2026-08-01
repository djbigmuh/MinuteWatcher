package com.example.minutewatcher.core.model

object Constants {
    const val PREFS_NAME = "app_prefs"

    object Notifications {
        const val CHANNEL_ID = "usage_monitor_channel"
    }

    object UI {
        const val DEFAULT_BG_COLOR: Int = 0xFF000000.toInt()
    }

    object Widget {
        const val FONT_SIZE_MEDIUM = 14f

        const val DEBOUNCE_MS = 300L
        const val REFRESH_TIMEOUT_MS = 3000L
    }

    object Traffic {
        const val MIN_DELTA_MB = 0.0001f
    }

    object Data {
        const val SNAPSHOT_TIMEOUT_MS = 2000L
    }

    object Semantic {
        const val THROTTLE_MS = 30_000L
        const val SEARCH_INDEX_TIMEOUT_MS = 10_000L
    }

    object Flow {
        const val STATE_FLOW_STOP_TIMEOUT_MS = 5_000L
    }

    const val BYTES_PER_MB = 1024L * 1024L
    const val BYTES_PER_GB = 1024L * 1024L * 1024L
    const val MB_PER_GB = 1024.0f
    const val LOW_REMAINING_THRESHOLD_PERCENT = 0.20f
    const val CRITICAL_REMAINING_THRESHOLD_PERCENT = 0.10f
    const val MAX_MINUTES_LIMIT = 44640
    const val MAX_TRAFFIC_LIMIT_GB = 10240f
}

object SimConstants {
    const val MAX_SUPPORTED_SIM_SLOTS = 3
    const val MOCK_SUB_ID_BASE = 1000
    val SUPPORTED_SLOT_INDICES = 0 until MAX_SUPPORTED_SIM_SLOTS
}
