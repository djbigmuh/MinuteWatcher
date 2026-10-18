package host.serv.djbigmuh.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for SIM usage limits and auto-reset settings.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Timeout bounding for repository operations is the responsibility of callers
 * (e.g. DataStoreRetryHelper, withTimeoutOrNull).
 */
interface SimLimitsRepository {
    /**
     * Returns a flow of the monthly minutes limit for the given identity.
     */
    fun getMonthlyLimit(identity: String): Flow<Int>

    /**
     * Returns a flow of the traffic limit for the given identity.
     */
    fun getTrafficLimit(identity: String): Flow<Int>

    /**
     * Updates SIM limits.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun updateSimLimits(
        identity: String,
        minutesLimit: Int,
        trafficLimitMb: Int,
        rolloverEnabled: Boolean = false,
    )

    /**
     * Returns a flow indicating if auto-reset is enabled for the given identity.
     */
    fun getAutoReset(identity: String): Flow<Boolean>

    /**
     * Returns a flow of the auto-reset day for the given identity.
     */
    fun getAutoResetDay(identity: String): Flow<Int>

    /**
     * Sets auto-reset settings.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setAutoReset(
        identity: String,
        day: Int,
        enabled: Boolean,
    )
}
