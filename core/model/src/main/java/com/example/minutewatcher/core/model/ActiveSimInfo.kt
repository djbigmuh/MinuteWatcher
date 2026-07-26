package com.example.minutewatcher.core.model

data class ActiveSimInfo(
    val identity: SimIdentity,
    val slotIndex: Int,
    val subscriptionId: Int,
    val displayName: String?,
    val carrierName: String?,
    val mccMnc: String?,
    val isDefaultData: Boolean,
    val isDefaultVoice: Boolean
)
