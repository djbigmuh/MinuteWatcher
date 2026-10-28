package host.serv.djbigmuh.minutewatcher.core.domain

import host.serv.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for managing the SIM identity seed.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Timeout bounding for repository operations is the responsibility of callers
 * (e.g. DataStoreRetryHelper, withTimeoutOrNull).
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
     * Operation write is bounded by [Constants.Flow.SHORT_TIMEOUT_MS].
     * On write timeout, logs a warning and completes silently without throwing an exception,
     * leaving any active ephemeral seed state unchanged.
     * On non-timeout storage errors (exceptions thrown by DataStore edit), rethrows the exception.
     *
     * @param seed Seed string to persist.
     * @throws Exception if non-timeout storage operation fails.
     * @throws CancellationException if the coroutine is canceled.
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
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun getOrCreateSeedOrEphemeralFallback(generator: () -> String): SeedResolutionResult

    /**
     * Returns the existing ephemeral seed or creates a new one using the provided generator.
     *
     * @param generator Function to generate a new seed if needed.
     * @return The ephemeral seed string.
     */
    suspend fun getOrCreateEphemeralSeed(generator: () -> String): String
}
