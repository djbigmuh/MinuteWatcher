package com.example.minutewatcher.core.model

object Constants {
    const val PREFS_NAME = "app_prefs"

    object Notifications {
        const val CHANNEL_ID = "usage_monitor_channel"
    }

    object UI {
        const val DEFAULT_BG_COLOR: Int = 0xFF000000.toInt()
        const val LUMINANCE_THRESHOLD = 0.5f
        const val SWITCH_SCALE = 0.8f
        const val HIGHLIGHT_DELAY_MS = 3000L

        /**
         * Порог (мс) для принудительного обновления при onResume.
         * Если прошло больше этого времени с последнего обновления — обновляем.
         * Отрицательный elapsed (перезагрузка устройства) всегда триггерит обновление.
         */
        const val RESUME_REFRESH_THRESHOLD_MS = 20_000L
    }

    object Widget {
        const val FONT_SIZE_MEDIUM = 14f

        const val DEBOUNCE_MS = 300L

        const val SIM_ARRANGEMENT_HORIZONTAL = 0

        const val TRANSPARENCY_SYSTEM_COLORS_THRESHOLD = 80

        const val PREEMPTIVE_LAYOUT_LIMIT_SIMS = 3
        const val MIN_SIMS_FOR_SEPARATOR = 2

        const val PERCENT_MAX_FLOAT = 100f
        const val SEPARATOR_ALPHA = 0.5f

        const val PADDING_DEFAULT = 8
        const val PADDING_SIM_ITEM = 4
        const val CORNER_RADIUS = 16
        const val SEPARATOR_THICKNESS = 1
        const val ALPHA_FULL = 1f

        const val RETRY_BACKOFF_MS = 500L
    }

    object Traffic {
        /**
         * Minimum change in traffic (in MB) required to trigger a repository update.
         * Used to filter out small measurement noise (e.g. background keep-alive packets).
         * 0.01 MB is approximately 10 KB.
         */
        const val MIN_DELTA_MB = 0.01f
        const val LOOKBACK_DAYS = 30L
    }

    object Data {
        const val SNAPSHOT_TIMEOUT_MS = 2000L
        const val EMPTY_TIMESTAMP = 0L
    }

    object Semantic {
        const val THROTTLE_MS = 30_000L
        const val SEARCH_INDEX_TIMEOUT_MS = 10_000L
    }

    object Flow {
        const val WHILE_SUBSCRIBED_STOP_TIMEOUT_MS = 5_000L
        const val DEBOUNCE_MS = 300L
        const val RETRY_TIMEOUT_MS = 5_000L
        const val SHORT_TIMEOUT_MS = 5_000L
        const val LONG_TIMEOUT_MS = 10_000L
        const val SEED_GENERATION_TIMEOUT_MS = 10_000L
        const val RETRY_DELAY_MS = 1_000L
    }

    object Calls {
        /**
         * Heuristic to identify stale call sessions.
         * If a call session exists for more than 12 hours without an IDLE event,
         * it is considered stale and can be cleaned up during ambiguous IDLE events.
         */
        const val MAX_CALL_DURATION_MS = 12 * 60 * 60 * 1000L
    }

    object IntentExtras {
        const val SIM_SLOT = "sim_slot"
    }

    object SimConstants {
        const val MAX_SUPPORTED_SIM_SLOTS = 10
        const val MOCK_SUB_ID_BASE = 1000
        val SUPPORTED_SLOT_INDICES = 0 until MAX_SUPPORTED_SIM_SLOTS
    }

    const val MILLIS_PER_SECOND = 1000L
    const val MILLIS_PER_DAY = 24 * 60 * 60 * MILLIS_PER_SECOND
    const val BYTES_PER_MB = 1024L * 1024L
    const val BYTES_PER_GB = 1024L * 1024L * 1024L
    const val MB_PER_GB = 1024.0f
    const val LOW_REMAINING_THRESHOLD_PERCENT = 0.20f
    const val CRITICAL_REMAINING_THRESHOLD_PERCENT = 0.10f
    const val MAX_MINUTES_LIMIT = 44640
    const val MAX_TRAFFIC_LIMIT_GB = 10240f
}
