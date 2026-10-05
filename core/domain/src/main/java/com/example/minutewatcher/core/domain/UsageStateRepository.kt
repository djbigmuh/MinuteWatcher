package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimCardUiModel
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
     * Returns true if the usage data has been loaded at least once.
     */
    fun isDataLoaded(): Boolean

    /**
     * Forces a refresh of all usage data.
     *
     * This operation is bounded by [com.example.minutewatcher.core.model.Constants.Data.SNAPSHOT_TIMEOUT_MS].
     *
     * @return [RefreshResult] indicating success or timeout.
     * @throws IOException if storage or network access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IOException::class)
    suspend fun refresh(): RefreshResult
}
