package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey

internal object NotificationSettingsKeys {
    fun notifiedLowMinutesKey(identity: String) = booleanPreferencesKey("notified_low_minutes_$identity")

    fun notifiedLowTrafficKey(identity: String) = booleanPreferencesKey("notified_low_traffic_$identity")

    fun notifiedExhaustedMinutesKey(identity: String) = booleanPreferencesKey("notified_exhausted_minutes_$identity")

    fun notifiedExhaustedTrafficKey(identity: String) = booleanPreferencesKey("notified_exhausted_traffic_$identity")
}
