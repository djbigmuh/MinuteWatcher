package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey

object WidgetSettingsKeys {
    val ALL_PREFIXES = listOf(
        "widget_show_sim_icon_",
        "widget_show_sim_name_",
        "widget_show_sim_",
        "widget_show_minutes_limit_",
        "widget_show_minutes_",
        "widget_show_traffic_limit_",
        "widget_show_traffic_",
    ).sortedByDescending { it.length }

    fun showSimKey(identity: String) = booleanPreferencesKey("widget_show_sim_$identity")

    fun showSimIconKey(identity: String) = booleanPreferencesKey("widget_show_sim_icon_$identity")

    fun showSimNameKey(identity: String) = booleanPreferencesKey("widget_show_sim_name_$identity")

    fun showMinutesKey(identity: String) = booleanPreferencesKey("widget_show_minutes_$identity")

    fun showMinutesLimitKey(identity: String) = booleanPreferencesKey("widget_show_minutes_limit_$identity")

    fun showTrafficKey(identity: String) = booleanPreferencesKey("widget_show_traffic_$identity")

    fun showTrafficLimitKey(identity: String) = booleanPreferencesKey("widget_show_traffic_limit_$identity")
}
