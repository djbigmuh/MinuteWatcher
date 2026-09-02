package com.example.minutewatcher.core.data.util

import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.util.mccStringCompat
import com.example.minutewatcher.core.common.util.mncStringCompat
import com.example.minutewatcher.core.model.ActiveSimInfo
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
    ): SimIdentity = createIdentity(info.subscriptionId, info.simSlotIndex, info.iccId, seed)

    fun createIdentityFromInfo(
        info: ActiveSimInfo,
        seed: String,
    ): SimIdentity = createIdentity(info.subscriptionId, info.slotIndex, info.iccId, seed)

    private fun createIdentity(
        subscriptionId: Int,
        slotIndex: Int,
        iccId: String?,
        seed: String,
    ): SimIdentity {
        return if (!iccId.isNullOrBlank()) {
            SimIdentity(
                stableId = "icc_" + hashString(iccId, seed),
                subscriptionId = subscriptionId,
                slotIndex = slotIndex,
                source = IdentitySource.ICCID,
                isStable = true,
            )
        } else {
            SimIdentity(
                stableId = "sub_$subscriptionId",
                subscriptionId = subscriptionId,
                slotIndex = slotIndex,
                source = IdentitySource.SUBSCRIPTION_ID,
                isStable = false,
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
