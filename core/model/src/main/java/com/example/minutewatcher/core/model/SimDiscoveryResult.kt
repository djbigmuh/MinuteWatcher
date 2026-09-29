package com.example.minutewatcher.core.model

sealed interface SimDiscoveryResult {
    data class Available(
        val sims: List<ActiveSimInfo>,
    ) : SimDiscoveryResult

    data object PermissionMissing : SimDiscoveryResult

    data object Unsupported : SimDiscoveryResult

    data class Failed(
        val reason: SimDiscoveryFailureReason,
    ) : SimDiscoveryResult
}

enum class SimDiscoveryFailureReason {
    STORAGE_ERROR,
    UNKNOWN,
}
