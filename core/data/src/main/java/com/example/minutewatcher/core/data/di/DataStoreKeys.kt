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
 * of truth for the DataStore schema within the module.
 *
 * **Strict API compliance:** Keys are implementation details of DataStore.
 * Public repository APIs must not expose them. This object is internal to core:data.
 *
 * **Migration safety:** When renaming keys, update both the key name and
 * add a migration step in IdentityMigrationService to preserve user data.
 */
internal object DataStoreKeys {
    // App settings (SettingsKeys)
    @JvmStatic val APP_THEME = intPreferencesKey("app_theme")

    @JvmStatic val APP_LANGUAGE = stringPreferencesKey("app_language")

    @JvmStatic val WIDGET_LAYOUT_MODE = intPreferencesKey("widget_layout_mode")

    @JvmStatic val WIDGET_SIM_ARRANGEMENT = intPreferencesKey("widget_sim_arrangement")

    @JvmStatic val WIDGET_TRANSPARENCY = intPreferencesKey("widget_transparency")

    @JvmStatic val WIDGET_FONT_SIZE = intPreferencesKey("widget_font_size")

    @JvmStatic val WIDGET_BACKGROUND_COLOR = intPreferencesKey("widget_background_color")

    @JvmStatic val NOTIFY_LOW_MINUTES = booleanPreferencesKey("notify_low_minutes")

    @JvmStatic val NOTIFY_LOW_TRAFFIC = booleanPreferencesKey("notify_low_traffic")

    @JvmStatic val LAST_DATA_SUB_ID = intPreferencesKey("last_data_sub_id")

    @JvmStatic val MIGRATION_V1_COMPLETED = booleanPreferencesKey("migration_v1_completed")

    @JvmStatic val MIGRATION_V1_SKIPPED_REASON = stringPreferencesKey("migration_v1_skipped_reason")

    @JvmStatic val MIGRATION_IDENTITY_STABILITY_COMPLETED =
        booleanPreferencesKey("migration_identity_stability_completed")

    @JvmStatic
    val INITIAL_SETUP_COMPLETED = booleanPreferencesKey("initial_setup_completed")

    @JvmStatic val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

    @JvmStatic val NOTIFICATION_PERMISSION_REQUESTED = booleanPreferencesKey("notification_permission_requested")

    @JvmStatic val WAS_PHONE_PERMISSION_REQUESTED_BEFORE =
        booleanPreferencesKey("was_phone_permission_requested_before")

    @JvmStatic val DATA_SCHEMA_VERSION = intPreferencesKey("data_schema_version")

    @JvmStatic val BENCHMARK_SIM_COUNT = intPreferencesKey("benchmark_sim_count")

    @JvmStatic val WIDGET_LIMIT_EXCEEDED = booleanPreferencesKey("widget_limit_exceeded")

    @JvmStatic val INACCURATE_CALL_ATTRIBUTIONS_COUNT = intPreferencesKey("inaccurate_call_attributions_count")

    @JvmStatic val CALL_TRACKING_ENABLED = booleanPreferencesKey("call_tracking_enabled")

    const val CURRENT_DATA_SCHEMA_VERSION = 2

    const val CALL_START_TIME_PREFIX = "call_start_time_"
    const val BOOT_TIME_PREFIX = "boot_time_"
    const val UPTIME_PREFIX = "uptime_"
    const val CALL_IDENTITY_PREFIX = "call_identity_"

    // Dynamic keys (DynamicSettingsKeys)
    @JvmStatic fun callStartTimeKey(subId: Int) = longPreferencesKey("$CALL_START_TIME_PREFIX$subId")

    @JvmStatic fun bootTimeKey(subId: Int) = longPreferencesKey("$BOOT_TIME_PREFIX$subId")

    @JvmStatic fun uptimeKey(subId: Int) = longPreferencesKey("$UPTIME_PREFIX$subId")

    @JvmStatic fun callIdentityKey(subId: Int) = stringPreferencesKey("$CALL_IDENTITY_PREFIX$subId")

    @JvmStatic fun lastTotalBytesKey(identity: String) = longPreferencesKey("last_total_bytes_$identity")

    @JvmStatic
    fun baselineRetryCountKey(identity: String) = intPreferencesKey("baseline_retry_count_$identity")

    // Notification states (NotificationSettingsKeys)
    @JvmStatic fun notifiedLowMinutesKey(identity: String) = booleanPreferencesKey("notified_low_minutes_$identity")

    @JvmStatic fun notifiedLowTrafficKey(identity: String) = booleanPreferencesKey("notified_low_traffic_$identity")

    @JvmStatic fun notifiedExhaustedMinutesKey(identity: String) =
        booleanPreferencesKey(
            "notified_exhausted_minutes_$identity",
        )

    @JvmStatic fun notifiedExhaustedTrafficKey(identity: String) =
        booleanPreferencesKey(
            "notified_exhausted_traffic_$identity",
        )

    // SIM settings (SimSettingsKeys)
    @JvmStatic fun monthlyLimitKey(identity: String) = intPreferencesKey("monthly_limit_$identity")

    @JvmStatic fun trafficLimitKey(identity: String) = intPreferencesKey("traffic_limit_$identity")

    @JvmStatic fun minutesUsedKey(identity: String) = intPreferencesKey("minutes_used_$identity")

    @JvmStatic fun manualAdjustmentKey(identity: String) = intPreferencesKey("manual_adjustment_$identity")

    @JvmStatic fun trafficAdjustmentKey(identity: String) = floatPreferencesKey("traffic_adjustment_$identity")

    @JvmStatic fun trafficUsedKey(identity: String) = floatPreferencesKey("traffic_used_$identity")

    @JvmStatic fun autoResetKey(identity: String) = booleanPreferencesKey("auto_reset_$identity")

    @JvmStatic fun autoResetDayKey(identity: String) = intPreferencesKey("auto_reset_day_$identity")

    @JvmStatic fun lastResetDateKey(identity: String) = longPreferencesKey("last_reset_date_$identity")

    @JvmStatic fun simOperatorKey(identity: String) = stringPreferencesKey("sim_operator_$identity")

    @JvmStatic fun customSimOperatorKey(identity: String) = stringPreferencesKey("custom_sim_operator_$identity")

    @JvmStatic fun simCodeKey(identity: String) = stringPreferencesKey("sim_code_$identity")

    @JvmStatic fun slotIndexKey(identity: String) = intPreferencesKey("slot_index_$identity")

    @JvmStatic fun subscriptionIdKey(identity: String) = intPreferencesKey("subscription_id_$identity")

    @JvmStatic fun rolloverEnabledKey(identity: String) = booleanPreferencesKey("rollover_enabled_$identity")

    // Widget settings (WidgetSettingsKeys)

    /**
     * Префиксы ключей настроек виджета, отсортированные по убыванию длины.
     *
     * **Важный инвариант (Strict Compliance):**
     * 1. Сортировка по убыванию длины необходима, чтобы префиксы типа `..._limit_`
     *    сопоставлялись раньше, чем их более короткие части (например, `..._`).
     *    Это предотвращает некорректное извлечение identity (например, получение
     *    "limit_icc_123" вместо "icc_123").
     * 2. Identity никогда не должны начинаться с "icon_" или "name_", так как это
     *    приведет к коллизиям с префиксами `widget_show_sim_icon_` и `widget_show_sim_name_`.
     *    Текущие форматы identity (`icc_...`, `sub_...`, `sim_...`) гарантируют отсутствие коллизий.
     */
    @JvmStatic val ALL_WIDGET_PREFIXES =
        listOf(
            "widget_show_sim_icon_",
            "widget_show_sim_name_",
            "widget_show_sim_",
            "widget_show_minutes_limit_",
            "widget_show_minutes_",
            "widget_show_traffic_limit_",
            "widget_show_traffic_",
        ).sortedByDescending { it.length }

    @JvmStatic fun showSimKey(identity: String) = booleanPreferencesKey("widget_show_sim_$identity")

    @JvmStatic fun showSimIconKey(identity: String) = booleanPreferencesKey("widget_show_sim_icon_$identity")

    @JvmStatic fun showSimNameKey(identity: String) = booleanPreferencesKey("widget_show_sim_name_$identity")

    @JvmStatic fun showMinutesKey(identity: String) = booleanPreferencesKey("widget_show_minutes_$identity")

    @JvmStatic fun showMinutesLimitKey(identity: String) = booleanPreferencesKey("widget_show_minutes_limit_$identity")

    @JvmStatic fun showTrafficKey(identity: String) = booleanPreferencesKey("widget_show_traffic_$identity")

    @JvmStatic fun showTrafficLimitKey(identity: String) = booleanPreferencesKey("widget_show_traffic_limit_$identity")

    // Identity Seed (SimIdentitySeedRepositoryImpl)
    @JvmStatic val SIM_IDENTITY_SEED = stringPreferencesKey("sim_identity_seed")

    @JvmStatic
    val EPHEMERAL_IDENTITY_SEED = stringPreferencesKey("ephemeral_identity_seed")
}
