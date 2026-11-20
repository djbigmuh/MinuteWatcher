package com.github.djbigmuh.minutewatcher.core.domain

/**
 * Result of the SIM identity seed resolution.
 * Distinguishes between persistent and ephemeral (fallback) seeds.
 */
sealed interface SeedResolutionResult {
    /**
     * The actual seed string.
     */
    val seed: String

    /**
     * Persistent seed stored in reliable storage.
     */
    data class Persistent(
        override val seed: String,
    ) : SeedResolutionResult

    /**
     * Ephemeral seed used as fallback when storage is unavailable.
     * Identities derived from this seed are unstable.
     */
    data class Ephemeral(
        override val seed: String,
    ) : SeedResolutionResult
}
