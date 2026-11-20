package com.github.djbigmuh.minutewatcher.core.model

sealed interface SimDiscoveryResult {
    /**
     * SIM discovery successful with active SIMs found.
     *
     * @property sims List of active SIM information objects.
     */
    data class Available(
        val sims: List<ActiveSimInfo>,
    ) : SimDiscoveryResult

    data object PermissionMissing : SimDiscoveryResult

    data object Unsupported : SimDiscoveryResult

    /**
     * SIM discovery failed with an error reason.
     *
     * @property reason Failure reason enum value.
     */
    data class Failed(
        val reason: SimDiscoveryFailureReason,
    ) : SimDiscoveryResult
}

enum class SimDiscoveryFailureReason {
    STORAGE_ERROR,
    API_ERROR,
    UNKNOWN,
}
