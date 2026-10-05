package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for SIM notification flags.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Timeout bounding for repository operations is the responsibility of callers
 * (e.g. DataStoreRetryHelper, withTimeoutOrNull).
 */
interface SimNotificationFlagsRepository {
    /**
     * Returns a flow indicating if the user was notified about low minutes.
     */
    fun getNotifiedLowMinutes(identity: String): Flow<Boolean>

    /**
     * Sets the notified state for low minutes.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setNotifiedLowMinutes(
        identity: String,
        notified: Boolean,
    )

    /**
     * Returns a flow indicating if the user was notified about low traffic.
     */
    fun getNotifiedLowTraffic(identity: String): Flow<Boolean>

    /**
     * Sets the notified state for low traffic.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setNotifiedLowTraffic(
        identity: String,
        notified: Boolean,
    )

    /**
     * Returns a flow indicating if the user was notified about exhausted minutes.
     */
    fun getNotifiedExhaustedMinutes(identity: String): Flow<Boolean>

    /**
     * Sets the notified state for exhausted minutes.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setNotifiedExhaustedMinutes(
        identity: String,
        notified: Boolean,
    )

    /**
     * Returns a flow indicating if the user was notified about exhausted traffic.
     */
    fun getNotifiedExhaustedTraffic(identity: String): Flow<Boolean>

    /**
     * Sets the notified state for exhausted traffic.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setNotifiedExhaustedTraffic(
        identity: String,
        notified: Boolean,
    )
}
