package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for managing the SIM identity seed.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Operations are typically bounded by SHORT_TIMEOUT_MS.
 */
interface SimIdentitySeedRepository {
    /**
     * Flow of the current seed string.
     */
    val seedFlow: Flow<String>

    /**
     * Whether an ephemeral (non-persistent) seed is currently active due to storage degradation.
     */
    val isEphemeralSeedActive: StateFlow<Boolean>

    /**
     * Sets the seed string.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setSeed(seed: String)

    /**
     * Returns the existing seed or creates a new one using the provided generator.
     *
     * **Important:** If DataStore is unavailable after repeated attempts, returns an ephemeral
     * seed which is NOT persisted. SIM identities will be unstable until storage access is restored.
     *
     * @param generator Function to generate a new seed if needed.
     * @return [SeedResolutionResult] containing either a persistent or ephemeral seed.
     * @throws IOException if critical storage access fails (though usually falls back to ephemeral).
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun getOrCreateSeedOrEphemeralFallback(generator: () -> String): SeedResolutionResult
}
