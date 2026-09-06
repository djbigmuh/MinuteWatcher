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
        isStable: Boolean = true,
    ): SimIdentity = createIdentity(info.subscriptionId, info.simSlotIndex, info.iccId, seed, isStable)

    fun createIdentityFromInfo(
        info: ActiveSimInfo,
        seed: String,
        isStable: Boolean = true,
    ): SimIdentity = createIdentity(info.subscriptionId, info.slotIndex, info.iccId, seed, isStable)

    private fun createIdentity(
        subscriptionId: Int,
        slotIndex: Int,
        iccId: String?,
        seed: String,
        isStable: Boolean,
    ): SimIdentity =
        if (!iccId.isNullOrBlank()) {
            SimIdentity(
                stableId = "icc_" + hashString(iccId, seed),
                subscriptionId = subscriptionId,
                slotIndex = slotIndex,
                source = IdentitySource.ICCID,
                isStable = isStable,
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

    private fun hashString(
        input: String,
        seed: String,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((input + seed).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
