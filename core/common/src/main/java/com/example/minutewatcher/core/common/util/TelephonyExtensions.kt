package com.example.minutewatcher.core.common.util

import android.os.Build
import android.telephony.SubscriptionInfo

/**
 * Compatibility extension for [SubscriptionInfo.getMccString].
 */
val SubscriptionInfo.mccStringCompat: String
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mccString ?: ""
        } else {
            @Suppress("DEPRECATION")
            mcc.toString()
        }

/**
 * Compatibility extension for [SubscriptionInfo.getMncString].
 */
val SubscriptionInfo.mncStringCompat: String
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mncString ?: ""
        } else {
            @Suppress("DEPRECATION")
            mnc.toString()
        }
