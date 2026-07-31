package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object SettingsKeys {
    val APP_THEME = intPreferencesKey("app_theme")
    val APP_LANGUAGE = stringPreferencesKey("app_language")
    val WIDGET_LAYOUT_MODE = intPreferencesKey("widget_layout_mode")
    val WIDGET_SIM_ARRANGEMENT = intPreferencesKey("widget_sim_arrangement")
    val WIDGET_TRANSPARENCY = intPreferencesKey("widget_transparency")
    val WIDGET_FONT_SIZE = intPreferencesKey("widget_font_size")
    val WIDGET_BACKGROUND_COLOR = intPreferencesKey("widget_background_color")
    val NOTIFY_LOW_MINUTES = booleanPreferencesKey("notify_low_minutes")
    val NOTIFY_LOW_TRAFFIC = booleanPreferencesKey("notify_low_traffic")
    val CALL_TRACKING_ENABLED = booleanPreferencesKey("call_tracking_enabled")
    val LAST_DATA_SUB_ID = intPreferencesKey("last_data_sub_id")
    val MIGRATION_V1_COMPLETED = booleanPreferencesKey("migration_v1_completed")
    val MIGRATION_V1_SKIPPED_REASON = stringPreferencesKey("migration_v1_skipped_reason")
    val MIGRATION_IDENTITY_STABILITY_COMPLETED = booleanPreferencesKey("migration_identity_stability_completed")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val NOTIFICATION_PERMISSION_REQUESTED = booleanPreferencesKey("notification_permission_requested")
    val PHONE_PERMISSION_REQUESTED = booleanPreferencesKey("phone_permission_requested")
    val DATA_SCHEMA_VERSION = intPreferencesKey("data_schema_version")

    const val CURRENT_DATA_SCHEMA_VERSION = 2

    fun callStartTimeKey(subId: Int) = longPreferencesKey("call_start_time_$subId")

    fun bootTimeKey(subId: Int) = longPreferencesKey("boot_time_$subId")

    fun uptimeKey(subId: Int) = longPreferencesKey("uptime_$subId")

    fun callIdentityKey(subId: Int) = stringPreferencesKey("call_identity_$subId")

    fun lastTotalBytesKey(identity: String) = longPreferencesKey("last_total_bytes_$identity")

    fun showSimKey(identity: String) = booleanPreferencesKey("widget_show_sim_$identity")

    fun showSimIconKey(identity: String) = booleanPreferencesKey("widget_show_sim_icon_$identity")

    fun showSimNameKey(identity: String) = booleanPreferencesKey("widget_show_sim_name_$identity")

    fun showMinutesKey(identity: String) = booleanPreferencesKey("widget_show_minutes_$identity")

    fun showMinutesLimitKey(identity: String) = booleanPreferencesKey("widget_show_minutes_limit_$identity")

    fun showTrafficKey(identity: String) = booleanPreferencesKey("widget_show_traffic_$identity")

    fun showTrafficLimitKey(identity: String) = booleanPreferencesKey("widget_show_traffic_limit_$identity")

    fun notifiedLowMinutesKey(identity: String) = booleanPreferencesKey("notified_low_minutes_$identity")

    fun notifiedLowTrafficKey(identity: String) = booleanPreferencesKey("notified_low_traffic_$identity")

    fun notifiedExhaustedMinutesKey(identity: String) = booleanPreferencesKey("notified_exhausted_minutes_$identity")

    fun notifiedExhaustedTrafficKey(identity: String) = booleanPreferencesKey("notified_exhausted_traffic_$identity")
}
