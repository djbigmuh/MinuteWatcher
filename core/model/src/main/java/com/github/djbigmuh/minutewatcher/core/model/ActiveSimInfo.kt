package com.github.djbigmuh.minutewatcher.core.model

/**
 * Active SIM card information.
 *
 * @property identity Stable SIM identity.
 * @property slotIndex SIM slot index.
 * @property subscriptionId Subscription ID.
 * @property iccId ICCID of the SIM card, or null if unavailable.
 * @property displayName Display name of the SIM card, or null if unset.
 * @property carrierName Carrier name of the SIM card, or null if unknown.
 * @property isDefaultData Whether this is the default data SIM.
 * @property isDefaultVoice Whether this is the default voice SIM.
 */
data class ActiveSimInfo(
    val identity: SimIdentity,
    val slotIndex: Int,
    val subscriptionId: Int,
    val iccId: String?,
    val displayName: String?,
    val carrierName: String?,
    val isDefaultData: Boolean,
    val isDefaultVoice: Boolean,
)
