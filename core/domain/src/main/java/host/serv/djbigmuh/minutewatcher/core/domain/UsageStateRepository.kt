package host.serv.djbigmuh.minutewatcher.core.domain

import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.SimCardUiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Result of a usage data refresh operation.
 */
sealed interface RefreshResult {
    /**
     * The snapshot of data after the refresh attempt.
     */
    val snapshot: GlobalSnapshot

    /**
     * Refresh was successful within expected time limits.
     */
    data class Success(
        override val snapshot: GlobalSnapshot,
    ) : RefreshResult

    /**
     * Refresh timed out, returning the last known snapshot.
     */
    data class Timeout(
        override val snapshot: GlobalSnapshot,
    ) : RefreshResult

    /**
     * Refresh failed due to an exception, returning the last known snapshot and the cause.
     */
    data class Failure(
        override val snapshot: GlobalSnapshot,
        val cause: Throwable,
    ) : RefreshResult
}

/**
 * Repository for observing the overall usage state and performing refreshes.
 *
 * All methods are safe to call from any coroutine context.
 * Data updates are expected to be atomic and comply with AOSP DataStore practices.
 */
interface UsageStateRepository {
    /**
     * Observes UI models for SIM cards.
     */
    fun observeSimCardUiModels(): Flow<List<SimCardUiModel>>

    /**
     * Returns the current state snapshot as a [StateFlow].
     */
    fun observeSnapshot(): StateFlow<GlobalSnapshot>

    /**
     * Returns true after the first real SIM discovery resolution has been processed:
     * - Immediately if discovery completes with an empty SIM list (e.g. no SIMs or permission denied).
     * - After the first usage data emission if discovery completes with active SIMs.
     */
    fun isDataLoaded(): Boolean

    /**
     * Forces a refresh of all usage data.
     *
     * This operation is bounded by [host.serv.djbigmuh.minutewatcher.core.model.Constants.Data.SNAPSHOT_TIMEOUT_MS].
     *
     * @return [RefreshResult] indicating success, timeout, or failure.
     * @throws IOException if storage or network access fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    @Throws(IOException::class)
    suspend fun refresh(): RefreshResult
}
