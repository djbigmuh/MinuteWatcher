package com.github.djbigmuh.minutewatcher.core.domain

import com.github.djbigmuh.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for SIM usage statistics.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Timeout bounding for repository operations is the responsibility of callers
 * (e.g. DataStoreRetryHelper, withTimeoutOrNull).
 */
interface SimUsageRepository {
    /**
     * Returns a flow of usage statistics for the given identity.
     */
    fun getSimUsage(identity: String): Flow<SimUsage>

    /**
     * Increments the minutes used for the given identity for a specific call.
     *
     * This operation is idempotent: if a call with the same [callSignature] has already been
     * counted, the method does nothing and returns false.
     *
     * The [callSignature] is a string signature of the call session composed of saved session
     * start time (wall clock, System.currentTimeMillis) and session start moment (SystemClock.elapsedRealtime)
     * separated by '|'. The signature is unique for each session and does not repeat across device reboots.
     *
     * @param identity The stable SIM identity.
     * @param deltaMinutes Number of minutes to add.
     * @param callSignature A unique string signature for the call session.
     * @return True if minutes were incremented, false if already counted or identity is invalid.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun incrementMinutesUsedForCall(
        identity: String,
        deltaMinutes: Int,
        callSignature: String,
    ): Boolean
}
