package com.github.djbigmuh.minutewatcher.core.domain

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Use case for managing SIM usage and operator information.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 */
interface UsageUseCase {
    /**
     * Refreshes operator information for all active SIMs.
     *
     * @throws IOException if system service or storage access fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun refreshOperatorInfo()

    /**
     * Adjusts the remaining minutes for a specific SIM slot.
     *
     * @param slot The SIM slot index.
     * @param remainingMinutes The new remaining minutes value.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun adjustMinutes(
        slot: Int,
        remainingMinutes: Int,
    )

    /**
     * Adjusts the remaining traffic for a specific SIM slot.
     *
     * @param slot The SIM slot index.
     * @param remainingGb The new remaining traffic in GB.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun adjustTraffic(
        slot: Int,
        remainingGb: Float,
    )

    /**
     * Resets usage counters for a specific SIM slot.
     *
     * @param slot The SIM slot index.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun resetCounters(slot: Int)
}
