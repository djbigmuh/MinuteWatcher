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
     * Increments the minutes used for the given identity for a specific call.
     *
     * This operation is idempotent: if a call with the same [callMarker] has already been
     * counted, the method does nothing and returns false.
     *
     * The [callMarker] should be the session's bootTime (elapsedRealtime of the call start),
     * which is unique within a device boot session. Marker collisions between different
     * boots are theoretically possible only if elapsedRealtime matches to the millisecond,
     * resulting in a skipped record, which is a conservative error.
     *
     * @param identity The stable SIM identity.
     * @param deltaMinutes Number of minutes to add.
     * @param callMarker A unique marker for the call session (session bootTime).
     * @return True if minutes were incremented, false if already counted or identity is invalid.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun incrementMinutesUsedForCall(
        identity: String,
        deltaMinutes: Int,
        callMarker: Long,
    ): Boolean

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
