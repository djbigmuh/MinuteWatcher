package com.github.djbigmuh.minutewatcher.core.data.util

import android.os.Build
import android.telephony.SubscriptionInfo
import androidx.annotation.RequiresApi
import com.github.djbigmuh.minutewatcher.core.common.util.mccStringCompat
import com.github.djbigmuh.minutewatcher.core.common.util.mncStringCompat
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import java.security.MessageDigest
import java.util.Locale

object SimIdentityUtils {
    /**
     * Returns all possible MCC/MNC string representations for the given [SubscriptionInfo].
     *
     * On API 29+, returns a single element list with the canonical string representation.
     * On API < 29, returns a list of candidates (raw, padded to 2, padded to 3 digits)
     * as the MNC length cannot be unambiguously determined from the integer value.
     */
    fun mccMncCandidates(info: SubscriptionInfo): List<String> {
        val mcc = info.mccStringCompat
        if (mcc.isEmpty()) return emptyList()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mccMncModern(mcc, info)
        } else {
            mccMncLegacy(mcc, info)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mccMncModern(
        mcc: String,
        info: SubscriptionInfo,
    ): List<String> {
        val mnc = info.mncString.orEmpty()
        return if (mnc.isNotEmpty()) listOf("$mcc$mnc") else emptyList()
    }

    private fun mccMncLegacy(
        mcc: String,
        info: SubscriptionInfo,
    ): List<String> {
        val mncRaw = info.mncStringCompat
        if (mncRaw.isEmpty()) return emptyList()

        return listOf(
            "$mcc$mncRaw",
            "$mcc${mncRaw.padStart(MNC_LEN_2, '0')}",
            "$mcc${mncRaw.padStart(MNC_LEN_3, '0')}",
        ).distinct()
    }

    private const val MNC_LEN_2 = 2
    private const val MNC_LEN_3 = 3

    /**
     * Creates a [SimIdentity] from a [SubscriptionInfo].
     *
     * If ICCID is missing (null or blank), the identity is constructed based on subscription ID
     * and always has `isStable = false` regardless of the [isStable] parameter value,
     * as subscription ID-based identities are inherently unstable across SIM re-insertions.
     */
    fun createIdentityFromInfo(
        info: SubscriptionInfo,
        seed: String,
        isStable: Boolean = true,
    ): SimIdentity = createIdentity(info.subscriptionId, info.simSlotIndex, info.iccId, seed, isStable)

    /**
     * Creates a [SimIdentity] from an [ActiveSimInfo].
     *
     * If ICCID is missing (null or blank), the identity is constructed based on subscription ID
     * and always has `isStable = false` regardless of the [isStable] parameter value,
     * as subscription ID-based identities are inherently unstable across SIM re-insertions.
     */
    fun createIdentityFromInfo(
        info: ActiveSimInfo,
        seed: String,
        isStable: Boolean = true,
    ): SimIdentity = createIdentity(info.subscriptionId, info.slotIndex, info.iccId, seed, isStable)

    /**
     * Constructs a [SimIdentity].
     *
     * If [iccId] is missing (null or blank), the identity is constructed based on subscription ID
     * and always has `isStable = false` regardless of the [isStable] parameter value.
     */
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
        val bytes = md.digest((input + seed).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(Locale.US, it) }
    }
}
