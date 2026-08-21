package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for SIM usage statistics.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Operations are typically bounded by SHORT_TIMEOUT_MS.
 */
interface SimUsageRepository {
    /**
     * Returns a flow of usage statistics for the given identity.
     */
    fun getSimUsage(identity: String): Flow<SimUsage>

    /**
     * Sets the minutes used for the given identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setMinutesUsed(
        identity: String,
        value: Int,
    )

    /**
     * Increments the minutes used for the given identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun incrementMinutesUsed(
        identity: String,
        delta: Int,
    )

    /**
     * Sets the traffic used in MB for the given identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setTrafficUsed(
        identity: String,
        valueMb: Float,
    )

    /**
     * Increments the traffic used in MB for the given identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun incrementTrafficUsed(
        identity: String,
        deltaMb: Float,
    )
}
