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
    val BENCHMARK_SIM_COUNT = intPreferencesKey("benchmark_sim_count")
    val WIDGET_LIMIT_EXCEEDED = booleanPreferencesKey("widget_limit_exceeded")
    val INACCURATE_CALL_ATTRIBUTIONS_COUNT = intPreferencesKey("inaccurate_call_attributions_count")

    const val CURRENT_DATA_SCHEMA_VERSION = 2
}
