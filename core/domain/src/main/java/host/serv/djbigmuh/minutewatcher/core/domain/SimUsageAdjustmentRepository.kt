package host.serv.djbigmuh.minutewatcher.core.domain

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for manual adjustments to SIM usage.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Timeout bounding for repository operations is the responsibility of callers
 * (e.g. DataStoreRetryHelper, withTimeoutOrNull).
 */
interface SimUsageAdjustmentRepository {
    /**
     * Sets the manual minutes adjustment for the given identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setManualAdjustment(
        identity: String,
        value: Int,
    )

    /**
     * Sets the traffic adjustment in GB for the given identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setTrafficAdjustment(
        identity: String,
        valueGb: Float,
    )

    /**
     * Updates multiple SIM statistics at once.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun updateSimStats(
        identity: String,
        minutesUsed: Int,
        trafficUsedMb: Float,
        manualAdjustment: Int,
    )
}
