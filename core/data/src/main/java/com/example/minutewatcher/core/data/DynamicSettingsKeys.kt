package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object DynamicSettingsKeys {
    fun callStartTimeKey(subId: Int) = longPreferencesKey("call_start_time_$subId")

    fun bootTimeKey(subId: Int) = longPreferencesKey("boot_time_$subId")

    fun uptimeKey(subId: Int) = longPreferencesKey("uptime_$subId")

    fun callIdentityKey(subId: Int) = stringPreferencesKey("call_identity_$subId")

    fun lastTotalBytesKey(identity: String) = longPreferencesKey("last_total_bytes_$identity")
}
