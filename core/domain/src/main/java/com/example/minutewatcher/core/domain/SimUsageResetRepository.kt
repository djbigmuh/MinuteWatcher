package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for resetting SIM usage statistics.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Operations are typically bounded by SHORT_TIMEOUT_MS.
 */
interface SimUsageResetRepository {
    /**
     * Resets SIM usage statistics.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun resetSimUsage(identity: String)

    /**
     * Resets SIM usage statistics manually.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun resetSimUsageManual(identity: String)

    /**
     * Resets SIM usage if the current day matches the auto-reset day.
     *
     * @return true if reset was performed.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun resetSimUsageIfDue(
        identity: String,
        autoResetDay: Int,
        nowMillis: Long,
    ): Boolean

    /**
     * Returns a flow of the last reset timestamp.
     */
    fun getLastResetDate(identity: String): Flow<Long>

    /**
     * Sets the last reset timestamp.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setLastResetDate(
        identity: String,
        value: Long,
    )

    /**
     * Returns a flow of the last total bytes seen at reset.
     */
    fun getLastTotalBytes(identity: String): Flow<Long>

    /**
     * Sets the last total bytes seen at reset.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setLastTotalBytes(
        identity: String,
        bytes: Long,
    )

    /**
     * Increment traffic usage and update baseline atomically in a single DataStore transaction.
     * Prevents data loss or double counting if one operation fails.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun incrementTrafficAndSetBaseline(
        identity: String,
        deltaMb: Float,
        totalBytes: Long,
    )
}
