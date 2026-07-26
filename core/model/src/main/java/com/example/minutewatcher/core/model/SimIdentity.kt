package com.example.minutewatcher.core.model

data class SimIdentity(
    val stableId: String,
    val subscriptionId: Int?,
    val slotIndex: Int?,
    val source: IdentitySource
)
