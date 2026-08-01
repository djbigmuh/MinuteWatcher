package com.example.minutewatcher.core.data.util

import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.util.mccStringCompat
import com.example.minutewatcher.core.common.util.mncStringCompat
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import java.security.MessageDigest

object SimIdentityUtils {
    fun getMccMnc(info: SubscriptionInfo): String {
        val mcc = info.mccStringCompat
        val mnc = info.mncStringCompat
        return if (mcc.isNotEmpty() && mnc.isNotEmpty()) "$mcc$mnc" else ""
    }

    fun createIdentityFromInfo(
        info: SubscriptionInfo,
        seed: String,
    ): SimIdentity {
        val iccid = info.iccId
        return if (!iccid.isNullOrBlank()) {
            SimIdentity(
                stableId = "icc_" + hashString(iccid, seed),
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                source = IdentitySource.ICCID,
            )
        } else {
            SimIdentity(
                stableId = "sub_${info.subscriptionId}",
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                source = IdentitySource.SUBSCRIPTION_ID,
            )
        }
    }

    private fun hashString(
        input: String,
        seed: String,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((input + seed).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
