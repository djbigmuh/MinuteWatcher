package host.serv.djbigmuh.minutewatcher.core.data.di

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Centralized registry of all DataStore preference keys.
 */
internal object DataStoreKeys {
    /**
     * **Schema Migration Invariant:** All DataStore key string values and prefixes defined here
     * represent the persistent storage schema contract. They MUST remain unchanged across schema versions
     * to ensure backward compatibility and prevent data loss or migration failures.
     */
    const val LAST_TOTAL_BYTES_PREFIX = "last_total_bytes_"
    const val NOTIFIED_LOW_MINUTES_PREFIX = "notified_low_minutes_"
    const val NOTIFIED_LOW_TRAFFIC_PREFIX = "notified_low_traffic_"
    const val NOTIFIED_EXHAUSTED_MINUTES_PREFIX = "notified_exhausted_minutes_"
    const val NOTIFIED_EXHAUSTED_TRAFFIC_PREFIX = "notified_exhausted_traffic_"
    const val MONTHLY_LIMIT_PREFIX = "monthly_limit_"
    const val TRAFFIC_LIMIT_PREFIX = "traffic_limit_"
    const val MINUTES_USED_PREFIX = "minutes_used_"
    const val LAST_COUNTED_CALL_MARKER_PREFIX = "last_counted_call_marker_"
    const val MANUAL_ADJUSTMENT_PREFIX = "manual_adjustment_"
    const val TRAFFIC_ADJUSTMENT_PREFIX = "traffic_adjustment_"
    const val TRAFFIC_USED_PREFIX = "traffic_used_"
    const val AUTO_RESET_PREFIX = "auto_reset_"
    const val AUTO_RESET_DAY_PREFIX = "auto_reset_day_"
    const val LAST_RESET_DATE_PREFIX = "last_reset_date_"
    const val SIM_OPERATOR_PREFIX = "sim_operator_"
    const val SLOT_INDEX_PREFIX = "slot_index_"
    const val ROLLOVER_ENABLED_PREFIX = "rollover_enabled_"
    const val WIDGET_SHOW_SIM_PREFIX = "widget_show_sim_"
    const val WIDGET_SHOW_SIM_ICON_PREFIX = "widget_show_sim_icon_"
    const val WIDGET_SHOW_SIM_NAME_PREFIX = "widget_show_sim_name_"
    const val WIDGET_SHOW_MINUTES_PREFIX = "widget_show_minutes_"
    const val WIDGET_SHOW_MINUTES_LIMIT_PREFIX = "widget_show_minutes_limit_"
    const val WIDGET_SHOW_TRAFFIC_PREFIX = "widget_show_traffic_"
    const val WIDGET_SHOW_TRAFFIC_LIMIT_PREFIX = "widget_show_traffic_limit_"

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

    val AOD_SUMMARY_ENABLED = booleanPreferencesKey("aod_summary_enabled")

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

    val USAGE_ACCESS_DIALOG_SHOWN =
        booleanPreferencesKey("usage_access_dialog_shown")

    val PHONE_PERMISSION_PERMANENTLY_DENIED = booleanPreferencesKey("phone_permission_permanently_denied")

    val NOTIFICATION_PERMISSION_PERMANENTLY_DENIED = booleanPreferencesKey("notification_permission_permanently_denied")

    val DATA_SCHEMA_VERSION = intPreferencesKey("data_schema_version")

    val BENCHMARK_SIM_COUNT = intPreferencesKey("benchmark_sim_count")

    val WIDGET_LIMIT_EXCEEDED = booleanPreferencesKey("widget_limit_exceeded")

    val AUTO_RESET_LAST_CHECK_MILLIS = longPreferencesKey("auto_reset_last_check_millis")

    val AUTO_RESET_FAIL_COUNT = intPreferencesKey("auto_reset_fail_count")

    val INACCURATE_CALL_ATTRIBUTIONS_COUNT = intPreferencesKey("inaccurate_call_attributions_count")

    val CALL_TRACKING_ENABLED = booleanPreferencesKey("call_tracking_enabled")

    val ASSISTANT_ACCESS_ENABLED = booleanPreferencesKey("assistant_access_enabled")

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

    fun lastTotalBytesKey(identity: String) = longPreferencesKey("$LAST_TOTAL_BYTES_PREFIX$identity")

    fun baselineRetryCountKey(identity: String) = intPreferencesKey("$BASELINE_RETRY_COUNT_PREFIX$identity")

    // Notification states (NotificationSettingsKeys)
    const val NOTIFIED_CRITICAL_MINUTES_PREFIX = "notified_critical_minutes_"
    const val NOTIFIED_CRITICAL_TRAFFIC_PREFIX = "notified_critical_traffic_"

    fun notifiedLowMinutesKey(identity: String) = booleanPreferencesKey("$NOTIFIED_LOW_MINUTES_PREFIX$identity")

    fun notifiedLowTrafficKey(identity: String) = booleanPreferencesKey("$NOTIFIED_LOW_TRAFFIC_PREFIX$identity")

    fun notifiedCriticalMinutesKey(identity: String) =
        booleanPreferencesKey("$NOTIFIED_CRITICAL_MINUTES_PREFIX$identity")

    fun notifiedCriticalTrafficKey(identity: String) =
        booleanPreferencesKey("$NOTIFIED_CRITICAL_TRAFFIC_PREFIX$identity")

    fun notifiedExhaustedMinutesKey(identity: String) =
        booleanPreferencesKey("$NOTIFIED_EXHAUSTED_MINUTES_PREFIX$identity")

    fun notifiedExhaustedTrafficKey(identity: String) =
        booleanPreferencesKey("$NOTIFIED_EXHAUSTED_TRAFFIC_PREFIX$identity")

    // SIM settings (SimSettingsKeys)
    fun monthlyLimitKey(identity: String) = intPreferencesKey("$MONTHLY_LIMIT_PREFIX$identity")

    fun trafficLimitKey(identity: String) = intPreferencesKey("$TRAFFIC_LIMIT_PREFIX$identity")

    fun minutesUsedKey(identity: String) = intPreferencesKey("$MINUTES_USED_PREFIX$identity")

    const val LAST_COUNTED_CALL_SIGNATURE_PREFIX = "last_counted_call_signature_"

    fun lastCountedCallSignatureKey(identity: String) =
        stringPreferencesKey("$LAST_COUNTED_CALL_SIGNATURE_PREFIX$identity")

    fun lastCountedCallMarkerKey(identity: String) = longPreferencesKey("$LAST_COUNTED_CALL_MARKER_PREFIX$identity")

    fun manualAdjustmentKey(identity: String) = intPreferencesKey("$MANUAL_ADJUSTMENT_PREFIX$identity")

    fun trafficAdjustmentKey(identity: String) = floatPreferencesKey("$TRAFFIC_ADJUSTMENT_PREFIX$identity")

    fun trafficUsedKey(identity: String) = floatPreferencesKey("$TRAFFIC_USED_PREFIX$identity")

    fun autoResetKey(identity: String) = booleanPreferencesKey("$AUTO_RESET_PREFIX$identity")

    fun autoResetDayKey(identity: String) = intPreferencesKey("$AUTO_RESET_DAY_PREFIX$identity")

    fun lastResetDateKey(identity: String) = longPreferencesKey("$LAST_RESET_DATE_PREFIX$identity")

    fun simOperatorKey(identity: String) = stringPreferencesKey("$SIM_OPERATOR_PREFIX$identity")

    const val CUSTOM_SIM_OPERATOR_PREFIX = "custom_sim_operator_"

    const val SIM_OPERATOR_BRAND_KEY_PREFIX = "sim_operator_brand_key_"

    fun simOperatorBrandKeyKey(identity: String) = stringPreferencesKey("$SIM_OPERATOR_BRAND_KEY_PREFIX$identity")

    fun slotIndexKey(identity: String) = intPreferencesKey("$SLOT_INDEX_PREFIX$identity")

    const val SUBSCRIPTION_ID_PREFIX = "subscription_id_"
    const val CONFLICT_PREFIX = "conflict_"

    fun subscriptionIdKey(identity: String) = intPreferencesKey("$SUBSCRIPTION_ID_PREFIX$identity")

    fun rolloverEnabledKey(identity: String) = booleanPreferencesKey("$ROLLOVER_ENABLED_PREFIX$identity")

    // Widget settings (WidgetSettingsKeys)

    fun showSimKey(identity: String) = booleanPreferencesKey("$WIDGET_SHOW_SIM_PREFIX$identity")

    fun showSimIconKey(identity: String) = booleanPreferencesKey("$WIDGET_SHOW_SIM_ICON_PREFIX$identity")

    fun showSimNameKey(identity: String) = booleanPreferencesKey("$WIDGET_SHOW_SIM_NAME_PREFIX$identity")

    fun showMinutesKey(identity: String) = booleanPreferencesKey("$WIDGET_SHOW_MINUTES_PREFIX$identity")

    fun showMinutesLimitKey(identity: String) = booleanPreferencesKey("$WIDGET_SHOW_MINUTES_LIMIT_PREFIX$identity")

    fun showTrafficKey(identity: String) = booleanPreferencesKey("$WIDGET_SHOW_TRAFFIC_PREFIX$identity")

    fun showTrafficLimitKey(identity: String) = booleanPreferencesKey("$WIDGET_SHOW_TRAFFIC_LIMIT_PREFIX$identity")

    // Identity Seed (SimIdentitySeedRepositoryImpl)
    val SIM_IDENTITY_SEED = stringPreferencesKey("sim_identity_seed")
}
