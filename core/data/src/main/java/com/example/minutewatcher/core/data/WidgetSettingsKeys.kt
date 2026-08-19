package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey

internal object WidgetSettingsKeys {
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
    val ALL_PREFIXES =
        listOf(
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
