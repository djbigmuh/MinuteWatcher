package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for resetting SIM usage statistics.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Timeout bounding for repository operations is the responsibility of callers
 * (e.g. DataStoreRetryHelper, withTimeoutOrNull).
 */
interface SimUsageResetRepository {
    /**
     * Resets SIM usage statistics manually.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun resetSimUsageManual(identity: String)

    /**
     * Resets SIM usage if a billing period boundary defined by [autoResetDay] has been crossed
     * since lastResetDate. If lastResetDate is unrecorded, opens the tracking window without clearing stats.
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
     * Returns a flow of the last total bytes seen at reset.
     */
    fun getLastTotalBytes(identity: String): Flow<Long>

    /**
     * Sets the last total bytes seen at reset if the stored lastResetDate equals expectedLastResetDate.
     *
     * @return true if write was applied, false if skipped due to window mismatch.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setLastTotalBytes(
        identity: String,
        bytes: Long,
        expectedLastResetDate: Long,
    ): Boolean

    /**
     * Increment traffic usage and update baseline atomically in a single DataStore transaction
     * if the stored lastResetDate equals expectedLastResetDate.
     * Prevents data loss or double counting if one operation fails or window changed.
     *
     * @return true if write was applied, false if skipped due to window mismatch.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun incrementTrafficAndSetBaseline(
        identity: String,
        deltaMb: Float,
        totalBytes: Long,
        expectedLastResetDate: Long,
    ): Boolean

    /**
     * Ensures that the tracking window (lastResetDate and lastTotalBytes) is initialized.
     * If lastResetDate is 0, the tracking window is opened at the current time and the baseline
     * is set to 0, because the window [now, now] contains no traffic.
     *
     * @return true if initialization was performed, false if already initialized.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun ensureTrackingWindowInitialized(identity: String): Boolean

    /**
     * Returns a flow of the baseline retry count for the identity.
     */
    fun getBaselineRetryCount(identity: String): Flow<Int?>

    /**
     * Sets the baseline retry count for the identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setBaselineRetryCount(
        identity: String,
        count: Int?,
    )

    /**
     * Clears retry counts for identities not in the [activeIdentities] set.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun cleanupBaselineRetries(activeIdentities: Set<String>)
}
