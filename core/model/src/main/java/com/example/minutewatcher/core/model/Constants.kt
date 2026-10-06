package com.example.minutewatcher.core.model

/**
 * Global application constants.
 * Contains constants for widgets, flows, SIM, notifications,
 * data, semantics, traffic, calls, and intent extras.
 *
 * All constants are documented with their strategy and reliability criteria.
 */
object Constants {
    /**
     * SharedPreferences name for DataStore migration.
     * Used in SharedPreferencesMigration during DataStore initialization.
     */
    const val PREFS_NAME = "app_prefs"

    /**
     * Legacy SharedPreferences name for SIM data, migrated to the SIM DataStore.
     */
    const val LEGACY_SIM_PREFS_NAME = "secret_usage_prefs"

    /**
     * Notification constants.
     *
     * CHANNEL_ID — Notification channel ID.
     */
    object Notifications {
        const val CHANNEL_ID = "usage_monitor_channel"
        const val AOD_SUMMARY_CHANNEL_ID = "aod_summary_channel"
    }

    object UI {
        const val DEFAULT_BG_COLOR: Int = 0xFF000000.toInt()
        const val LUMINANCE_THRESHOLD = 0.5f
        const val SWITCH_SCALE = 0.8f
        const val HIGHLIGHT_DELAY_MS = 3000L

        /**
         * Minimum total horizontal drag distance (in dp) to trigger navigation swipe gesture.
         * Density-independent threshold.
         */
        const val SWIPE_NAVIGATION_THRESHOLD_DP = 72
    }

    /**
     * Widget constants.
     *
     * FONT_SIZE_MEDIUM — default widget font size (sp).
     * DEBOUNCE_MS — debounce delay for reactive widget updates (ms).
     *   Prevents multiple updates during rapid data changes.
     * SIM_ARRANGEMENT_HORIZONTAL — horizontal SIM arrangement constant.
     * SIM_ARRANGEMENT_VERTICAL — vertical SIM arrangement constant.
     * TRANSPARENCY_SYSTEM_COLORS_THRESHOLD — transparency threshold (0-100),
     *   above which system colors are used.
     *   Chosen as a balance between legibility and aesthetics.
     * PREEMPTIVE_LAYOUT_LIMIT_SIMS — number of SIMs at which layout is preemptively
     *   restricted. On AOSP RemoteViews limit is ~1 MB.
     *   3 SIMs × 6 lines × ~500 B ≈ 9 KB — safe.
     *   Simplified layout is applied when widgetLimitExceeded = true.
     * MIN_SIMS_FOR_SEPARATOR — minimum number of SIMs to show separator.
     * PERCENT_MAX_FLOAT — maximum transparency value (100%).
     * SEPARATOR_ALPHA — separator alpha transparency (0.5).
     * PADDING_DEFAULT — default widget padding (dp).
     * PADDING_SIM_ITEM — SIM item padding in widget (dp).
     * CORNER_RADIUS — widget corner radius (dp).
     * SEPARATOR_THICKNESS — separator thickness (dp).
     * ALPHA_FULL — full opacity (1.0).
     * RETRY_BACKOFF_MS — initial widget update retry backoff delay (ms).
     * MAX_RETRY_DELAY_MS — maximum delay between retries (ms).
     * MAX_UPDATE_ATTEMPTS — maximum number of widget update attempts.
     */
    object Widget {
        const val FONT_SIZE_MEDIUM = 14f
        const val DEBOUNCE_MS = 300L
        const val SIM_ARRANGEMENT_HORIZONTAL = 0
        const val SIM_ARRANGEMENT_VERTICAL = 1
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
        const val MAX_RETRY_DELAY_MS = 3000L
        const val MAX_UPDATE_ATTEMPTS = 3

        /** Maximum number of consecutive widget update failures before locking. */
        const val MAX_CONSECUTIVE_UPDATE_FAILURES = 5

        const val MIN_LAYOUT_MODE = 0

        /**
         * Upper bound for user-selectable widget layout modes (0..3: Single, Two, Three, Six lines).
         * Note: Mode 4 (SIMPLE) is an internal render fallback mode when widget limits are exceeded,
         * and is not selectable by the user in settings.
         */
        const val MAX_SELECTABLE_LAYOUT_MODE = 3

        /** Upper bound for effective render layout modes (0..4), including internal fallback SIMPLE (4). */
        const val MAX_LAYOUT_MODE = 4
        const val DEFAULT_LAYOUT_MODE = 2
        const val MIN_SIM_ARRANGEMENT = SIM_ARRANGEMENT_HORIZONTAL
        const val MAX_SIM_ARRANGEMENT = SIM_ARRANGEMENT_VERTICAL
        const val MIN_TRANSPARENCY = 0
        const val MAX_TRANSPARENCY = 100
        const val DEFAULT_TRANSPARENCY = 50
        const val MIN_FONT_SIZE = 0
        const val MAX_FONT_SIZE = 4
        const val DEFAULT_FONT_SIZE_INDEX = 2
    }

    /**
     * Traffic constants.
     *
     * MIN_DELTA_MB — minimum traffic change (MB) for an update.
     *   Prevents updates on minor changes
     *   (e.g., background keep-alive packets).
     *   Heuristic within reliability criteria: 0.01 MB ≈ 10 KB.
     */
    object Traffic {
        const val MIN_DELTA_MB = 0.01f
    }

    /**
     * Data constants.
     *
     * SNAPSHOT_TIMEOUT_MS — snapshot update timeout (ms).
     * STALE_SNAPSHOT_THRESHOLD_MS — snapshot staleness threshold (ms).
     *   If snapshot is older than this threshold, widget displays a stale
     *   data indicator.
     * EMPTY_TIMESTAMP — empty timestamp (0). Used to indicate
     *   missing data.
     */
    object Data {
        const val SNAPSHOT_TIMEOUT_MS = 2000L
        const val STALE_SNAPSHOT_THRESHOLD_MS = 60_000L
        const val EMPTY_TIMESTAMP = 0L
    }

    /**
     * Semantic constants.
     *
     * THROTTLE_MS — throttle delay for semantic updates (ms).
     *   Prevents multiple updates during rapid data changes.
     * SEARCH_INDEX_TIMEOUT_MS — search indexing timeout (ms).
     *   Strict API compliance: on AOSP indexing finishes in milliseconds.
     *   Timeout indicates degradation on custom firmware.
     */
    object Semantic {
        const val THROTTLE_MS = 30_000L
        const val SEARCH_INDEX_TIMEOUT_MS = 10_000L

        /**
         * Timeout waiting for initial loaded snapshot (ms).
         * Bounded wait for the first snapshot with non-empty timestamp during semantic
         * services initialization; matched to typical worker initialization budget.
         */
        const val INITIAL_SNAPSHOT_TIMEOUT_MS = 15_000L
    }

    /**
     * Time constants.
     */
    object Time {
        /** Number of milliseconds in one second. */
        const val MILLIS_IN_SECOND = 1000L

        /** Number of seconds in one minute. */
        const val SECONDS_IN_MINUTE = 60

        /** Number of minutes in one hour. */
        const val MINUTES_IN_HOUR = 60

        /** Widget update timeout (ms). */
        const val WIDGET_UPDATE_TIMEOUT_MS = 10_000L
    }

    /**
     * Auto-reset day-of-month bounds.
     * MAX_DAY equals the maximum number of days in a calendar month.
     */
    object AutoReset {
        const val MIN_DAY = 1
        const val MAX_DAY = 31
    }

    /**
     * Flow constants.
     *
     * WHILE_SUBSCRIBED_STOP_TIMEOUT_MS — WhileSubscribed stop timeout (ms).
     *   Sufficient for brief screen rotations without restarting Flow.
     * DEBOUNCE_MS — general debounce for UI flows (ms).
     * SHORT_TIMEOUT_MS — timeout for "fast" operations (ms).
     * LONG_TIMEOUT_MS — timeout for "heavy" operations (ms).
     * RETRY_READ_ATTEMPT_TIMEOUT_MS — timeout for a single DataStore retry read attempt (ms).
     *   On AOSP DataStore reads take < 100 ms; timeout triggers only on storage degradation.
     * RETRY_DELAY_MS — retry delay on network/disk failures (ms).
     * SEED_GENERATION_TIMEOUT_MS — primary seed identity generation timeout (ms).
     * SEED_TOTAL_TIMEOUT_EXTRA_MS — additional wait time for completing
     *   all background tasks during seed generation.
     */
    object Flow {
        const val WHILE_SUBSCRIBED_STOP_TIMEOUT_MS = 5_000L
        const val DEFAULT_READ_TIMEOUT_MS = 5_000L
        const val DEBOUNCE_MS = 100L
        const val SHORT_TIMEOUT_MS = 5_000L
        const val LONG_TIMEOUT_MS = 10_000L
        const val INITIAL_SETUP_RETRY_TIMEOUT_MS = 15_000L
        const val RETRY_READ_ATTEMPT_TIMEOUT_MS = 2_000L
        const val SEED_GENERATION_TIMEOUT_MS = 10_000L
        const val RETRY_DELAY_MS = 1_000L

        /**
         * Total timeout for a single traffic update cycle.
         * Includes data fetching, calculations, and persistence.
         * Adjusted to accommodate sequential DataStore operations.
         */
        const val TRAFFIC_UPDATE_TOTAL_TIMEOUT_MS = 25_000L
    }

    /**
     * Call constants.
     *
     * MAX_CALL_DURATION_MS — maximum call duration (ms).
     *   If a call lasts longer, it is considered "hung" and the session
     *   is cleared. This prevents endless minute accumulation.
     *   Heuristic within reliability criteria: 12 hours is a reasonable
     *   maximum for a single call.
     */
    object Calls {
        private const val MAX_CALL_DURATION_HOURS = 12L

        const val MAX_CALL_DURATION_MS =
            MAX_CALL_DURATION_HOURS * Time.MINUTES_IN_HOUR * Time.SECONDS_IN_MINUTE * Time.MILLIS_IN_SECOND

        /**
         * Threshold for showing inaccurate attribution banner.
         * Why 3: three consecutive unassigned events indicate a systemic issue
         * rather than a transient race condition.
         */
        const val INACCURATE_ATTRIBUTION_BANNER_THRESHOLD = 3
    }

    /**
     * Intent extras constants.
     *
     * SIM_SLOT — key for passing SIM slot index in intent.
     */
    object IntentExtras {
        const val SIM_SLOT = "sim_slot"
    }

    /**
     * SIM constants.
     *
     * MAX_SUPPORTED_SIM_SLOTS — maximum number of supported SIM slots.
     * SUPPORTED_SLOT_INDICES — range of supported slot indices.
     */
    object SimConstants {
        const val MAX_SUPPORTED_SIM_SLOTS = 10
        val SUPPORTED_SLOT_INDICES = 0 until MAX_SUPPORTED_SIM_SLOTS
    }

    /**
     * Number of bytes in a megabyte (1024 × 1024 = 1048576).
     */
    const val BYTES_PER_MB = 1024L * 1024L

    /**
     * Number of bytes in a gigabyte (1024 × 1024 × 1024 = 1073741824).
     */
    const val BYTES_PER_GB = 1024L * 1024L * 1024L

    /**
     * Number of megabytes in a gigabyte (1024.0).
     * Float for use in division.
     */
    const val MB_PER_GB = 1024.0f

    /** Critical remaining threshold in percent (integer, 10%). */
    const val CRITICAL_THRESHOLD_PERCENT_INT = 10

    /** Low remaining threshold in percent (integer, 20%). */
    const val LOW_THRESHOLD_PERCENT_INT = 20

    /**
     * Critical remaining threshold as a fraction (0.10f).
     * Derived from [CRITICAL_THRESHOLD_PERCENT_INT].
     */
    const val CRITICAL_REMAINING_THRESHOLD_FRACTION = CRITICAL_THRESHOLD_PERCENT_INT / 100f

    /**
     * Low remaining threshold as a fraction (0.20f).
     * Derived from [LOW_THRESHOLD_PERCENT_INT].
     */
    const val LOW_REMAINING_THRESHOLD_FRACTION = LOW_THRESHOLD_PERCENT_INT / 100f

    /**
     * Maximum minutes limit (44640 = 31 days × 24 hours × 60 minutes).
     * Chosen as a reasonable maximum for monthly minutes limit.
     */
    const val MAX_MINUTES_LIMIT = 44640

    /**
     * Maximum traffic limit in GB (10240 = 10 TB).
     * Chosen as a reasonable maximum for monthly traffic limit.
     * At values above this, Float loses precision below 1 MB.
     */
    const val MAX_TRAFFIC_LIMIT_GB = 10240f
}
