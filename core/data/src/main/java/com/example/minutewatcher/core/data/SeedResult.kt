package com.example.minutewatcher.core.data

/**
 * Result of the SIM identity seed resolution.
 */
internal sealed interface SeedResult {
    data class Real(
        val value: String,
    ) : SeedResult

    data object Fallback : SeedResult
}
