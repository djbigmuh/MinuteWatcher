package com.github.djbigmuh.minutewatcher.core.common.util

import android.os.Build
import android.telephony.SubscriptionInfo

/**
 * Compatibility extension for [SubscriptionInfo.getMccString].
 *
 * NOTE: [Integer.MAX_VALUE] is the value used by the framework on API < 29
 * to represent unavailable MCC.
 */
val SubscriptionInfo.mccStringCompat: String
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mccString.orEmpty()
        } else {
            // @Suppress("DEPRECATION") is required for backward compatibility with API < 29
            // where mcc/mnc fields are the only way to get these values.
            // MCC is always 3 digits according to ITU E.212.
            @Suppress("DEPRECATION")
            if (mcc == Integer.MAX_VALUE) "" else mcc.toString().padStart(3, '0')
        }

/**
 * Compatibility extension for [SubscriptionInfo.getMncString].
 *
 * NOTE: [Integer.MAX_VALUE] is the value used by the framework on API < 29
 * to represent unavailable MNC.
 */
val SubscriptionInfo.mncStringCompat: String
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mncString.orEmpty()
        } else {
            // @Suppress("DEPRECATION") is required for backward compatibility with API < 29
            // where mcc/mnc fields are the only way to get these values.
            @Suppress("DEPRECATION")
            if (mnc == Integer.MAX_VALUE) "" else mnc.toString()
        }
