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
            // @Suppress("DEPRECATION") is required for backward compatibility with API < 29
            // where mcc/mnc fields are the only way to get these values.
            // MCC is always 3 digits according to ITU E.212.
            @Suppress("DEPRECATION")
            mcc.toString().padStart(3, '0')
        }

/**
 * Compatibility extension for [SubscriptionInfo.getMncString].
 */
val SubscriptionInfo.mncStringCompat: String
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mncString ?: ""
        } else {
            // @Suppress("DEPRECATION") is required for backward compatibility with API < 29
            // where mcc/mnc fields are the only way to get these values.
            @Suppress("DEPRECATION")
            mnc.toString()
        }
