package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object DynamicSettingsKeys {
    const val CALL_START_TIME_PREFIX = "call_start_time_"
    const val BOOT_TIME_PREFIX = "boot_time_"
    const val UPTIME_PREFIX = "uptime_"
    const val CALL_IDENTITY_PREFIX = "call_identity_"

    fun callStartTimeKey(subId: Int) = longPreferencesKey("$CALL_START_TIME_PREFIX$subId")

    fun bootTimeKey(subId: Int) = longPreferencesKey("$BOOT_TIME_PREFIX$subId")

    fun uptimeKey(subId: Int) = longPreferencesKey("$UPTIME_PREFIX$subId")

    fun callIdentityKey(subId: Int) = stringPreferencesKey("$CALL_IDENTITY_PREFIX$subId")

    fun lastTotalBytesKey(identity: String) = longPreferencesKey("last_total_bytes_$identity")
}
