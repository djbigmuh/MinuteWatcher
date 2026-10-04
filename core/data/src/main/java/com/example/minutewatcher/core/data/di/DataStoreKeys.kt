package com.example.minutewatcher.core.data.di

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Centralized registry of all DataStore preference keys.
 *
 * **Architectural Note:** Multiple helper functions are used here to ensure
 * type-safe access to dynamic preference keys (e.g., identity-based keys).
 * This centralized approach prevents key collisions and serves as the single source
 * of truth for the DataStore schema within the module. Decomposition is counterproductive
 * as it would fragment the schema definition.
 */
@Suppress("TooManyFunctions")
internal object DataStoreKeys {
    // App settings (SettingsKeys)
    val APP_THEME = intPreferencesKey("app_theme")

    val APP_LANGUAGE = stringPreferencesKey("app_language")

    val WIDGET_LAYOUT_MODE = intPreferencesKey("widget_layout_mode")

    val WIDGET_SIM_ARRANGEMENT = intPreferencesKey("widget_sim_arrangement")

    val WIDGET_TRANSPARENCY = intPreferencesKey("widget_transparency")

    val WIDGET_FONT_SIZE = intPreferencesKey("widget_font_size")

    val WIDGET_BACKGROUND_COLOR = intPreferencesKey("widget_background_color")

    val NOTIFY_LOW_MINUTES = booleanPreferencesKey("notify_low_minutes")

    val NOTIFY_LOW_TRAFFIC = booleanPreferencesKey("notify_low_traffic")

    val LAST_DATA_SUB_ID = intPreferencesKey("last_data_sub_id")

    val MIGRATION_V1_COMPLETED = booleanPreferencesKey("migration_v1_completed")

    val MIGRATION_V1_SKIPPED_REASON = stringPreferencesKey("migration_v1_skipped_reason")

    val MIGRATION_IDENTITY_STABILITY_COMPLETED =
        booleanPreferencesKey("migration_identity_stability_completed")

    val INITIAL_SETUP_COMPLETED = booleanPreferencesKey("initial_setup_completed")

    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

    val NOTIFICATION_PERMISSION_REQUESTED = booleanPreferencesKey("notification_permission_requested")

    val WAS_PHONE_PERMISSION_REQUESTED_BEFORE =
        booleanPreferencesKey("was_phone_permission_requested_before")

    val DATA_SCHEMA_VERSION = intPreferencesKey("data_schema_version")

    val BENCHMARK_SIM_COUNT = intPreferencesKey("benchmark_sim_count")

    val WIDGET_LIMIT_EXCEEDED = booleanPreferencesKey("widget_limit_exceeded")

    val AUTO_RESET_LAST_CHECK_MILLIS = longPreferencesKey("auto_reset_last_check_millis")

    val AUTO_RESET_FAIL_COUNT = intPreferencesKey("auto_reset_fail_count")

    val INACCURATE_CALL_ATTRIBUTIONS_COUNT = intPreferencesKey("inaccurate_call_attributions_count")

    val CALL_TRACKING_ENABLED = booleanPreferencesKey("call_tracking_enabled")

    const val CURRENT_DATA_SCHEMA_VERSION = 2

    const val CALL_START_TIME_PREFIX = "call_start_time_"
    const val BOOT_TIME_PREFIX = "boot_time_"
    const val UPTIME_PREFIX = "uptime_"
    const val CALL_IDENTITY_PREFIX = "call_identity_"
    const val BASELINE_RETRY_COUNT_PREFIX = "baseline_retry_count_"

    // Dynamic keys (DynamicSettingsKeys)
    fun callStartTimeKey(subId: Int) = longPreferencesKey("$CALL_START_TIME_PREFIX$subId")

    fun bootTimeKey(subId: Int) = longPreferencesKey("$BOOT_TIME_PREFIX$subId")

    fun uptimeKey(subId: Int) = longPreferencesKey("$UPTIME_PREFIX$subId")

    fun callIdentityKey(subId: Int) = stringPreferencesKey("$CALL_IDENTITY_PREFIX$subId")

    fun lastTotalBytesKey(identity: String) = longPreferencesKey("last_total_bytes_$identity")

    fun baselineRetryCountKey(identity: String) = intPreferencesKey("$BASELINE_RETRY_COUNT_PREFIX$identity")

    // Notification states (NotificationSettingsKeys)
    fun notifiedLowMinutesKey(identity: String) = booleanPreferencesKey("notified_low_minutes_$identity")

    fun notifiedLowTrafficKey(identity: String) = booleanPreferencesKey("notified_low_traffic_$identity")

    fun notifiedExhaustedMinutesKey(identity: String) =
        booleanPreferencesKey(
            "notified_exhausted_minutes_$identity",
        )

    fun notifiedExhaustedTrafficKey(identity: String) =
        booleanPreferencesKey(
            "notified_exhausted_traffic_$identity",
        )

    // SIM settings (SimSettingsKeys)
    fun monthlyLimitKey(identity: String) = intPreferencesKey("monthly_limit_$identity")

    fun trafficLimitKey(identity: String) = intPreferencesKey("traffic_limit_$identity")

    fun minutesUsedKey(identity: String) = intPreferencesKey("minutes_used_$identity")

    const val LAST_COUNTED_CALL_SIGNATURE_PREFIX = "last_counted_call_signature_"

    fun lastCountedCallSignatureKey(identity: String) =
        stringPreferencesKey("$LAST_COUNTED_CALL_SIGNATURE_PREFIX$identity")

    fun lastCountedCallMarkerKey(identity: String) = longPreferencesKey("last_counted_call_marker_$identity")

    fun manualAdjustmentKey(identity: String) = intPreferencesKey("manual_adjustment_$identity")

    fun trafficAdjustmentKey(identity: String) = floatPreferencesKey("traffic_adjustment_$identity")

    fun trafficUsedKey(identity: String) = floatPreferencesKey("traffic_used_$identity")

    fun autoResetKey(identity: String) = booleanPreferencesKey("auto_reset_$identity")

    fun autoResetDayKey(identity: String) = intPreferencesKey("auto_reset_day_$identity")

    fun lastResetDateKey(identity: String) = longPreferencesKey("last_reset_date_$identity")

    fun simOperatorKey(identity: String) = stringPreferencesKey("sim_operator_$identity")

    fun customSimOperatorKey(identity: String) = stringPreferencesKey("custom_sim_operator_$identity")

    fun slotIndexKey(identity: String) = intPreferencesKey("slot_index_$identity")

    const val SUBSCRIPTION_ID_PREFIX = "subscription_id_"
    const val CONFLICT_PREFIX = "conflict_"

    fun subscriptionIdKey(identity: String) = intPreferencesKey("$SUBSCRIPTION_ID_PREFIX$identity")

    fun rolloverEnabledKey(identity: String) = booleanPreferencesKey("rollover_enabled_$identity")

    // Widget settings (WidgetSettingsKeys)

    fun showSimKey(identity: String) = booleanPreferencesKey("widget_show_sim_$identity")

    fun showSimIconKey(identity: String) = booleanPreferencesKey("widget_show_sim_icon_$identity")

    fun showSimNameKey(identity: String) = booleanPreferencesKey("widget_show_sim_name_$identity")

    fun showMinutesKey(identity: String) = booleanPreferencesKey("widget_show_minutes_$identity")

    fun showMinutesLimitKey(identity: String) = booleanPreferencesKey("widget_show_minutes_limit_$identity")

    fun showTrafficKey(identity: String) = booleanPreferencesKey("widget_show_traffic_$identity")

    fun showTrafficLimitKey(identity: String) = booleanPreferencesKey("widget_show_traffic_limit_$identity")

    // Identity Seed (SimIdentitySeedRepositoryImpl)
    val SIM_IDENTITY_SEED = stringPreferencesKey("sim_identity_seed")
}
