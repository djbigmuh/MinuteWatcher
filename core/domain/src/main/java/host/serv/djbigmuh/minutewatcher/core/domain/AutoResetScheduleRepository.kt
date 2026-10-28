package host.serv.djbigmuh.minutewatcher.core.domain

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for persisting auto-reset schedule state.
 *
 * All methods are safe to call from any coroutine context.
 */
interface AutoResetScheduleRepository {
    /**
     * Returns the timestamp (wall clock millis) of the last auto-reset check,
     * or null if no timestamp is saved or if reading timed out.
     */
    suspend fun getLastCheckMillis(): Long?

    /**
     * Sets the timestamp (wall clock millis) of the last auto-reset check.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun setLastCheckMillis(valueMillis: Long)

    /**
     * Returns the consecutive failure count of auto-reset checks,
     * or 0 if no count is saved or if reading timed out.
     */
    suspend fun getFailureCount(): Int

    /**
     * Sets the consecutive failure count of auto-reset checks.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun setFailureCount(count: Int)
}
