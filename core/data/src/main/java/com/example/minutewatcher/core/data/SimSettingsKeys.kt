package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object SimSettingsKeys {
    fun monthlyLimitKey(identity: String) = intPreferencesKey("monthly_limit_$identity")
    fun trafficLimitKey(identity: String) = intPreferencesKey("traffic_limit_$identity")
    fun minutesUsedKey(identity: String) = intPreferencesKey("minutes_used_$identity")
    fun manualAdjustmentKey(identity: String) = intPreferencesKey("manual_adjustment_$identity")
    fun trafficAdjustmentKey(identity: String) = floatPreferencesKey("traffic_adjustment_$identity")
    fun trafficUsedKey(identity: String) = floatPreferencesKey("traffic_used_$identity")
    fun autoResetKey(identity: String) = booleanPreferencesKey("auto_reset_$identity")
    fun autoResetDayKey(identity: String) = intPreferencesKey("auto_reset_day_$identity")
    fun lastResetDateKey(identity: String) = longPreferencesKey("last_reset_date_$identity")
    fun simOperatorKey(identity: String) = stringPreferencesKey("sim_operator_$identity")
    fun simCodeKey(identity: String) = stringPreferencesKey("sim_code_$identity")
    fun slotIndexKey(identity: String) = intPreferencesKey("slot_index_$identity")
    fun subscriptionIdKey(identity: String) = intPreferencesKey("subscription_id_$identity")
    fun rolloverEnabledKey(identity: String) = booleanPreferencesKey("rollover_enabled_$identity")
}
