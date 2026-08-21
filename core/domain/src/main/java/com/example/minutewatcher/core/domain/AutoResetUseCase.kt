package com.example.minutewatcher.core.domain

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Use case for performing automatic reset of SIM usage statistics.
 *
 * All methods are safe to call from any coroutine context (Main, IO, Default).
 * Implementation must comply with AOSP DataStore best practices, ensuring atomic operations.
 */
interface AutoResetUseCase {
    /**
     * Performs auto-reset for all SIM cards if they reached their reset day.
     *
     * @param shouldNotify Whether to show notification about reset.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun performAutoReset(shouldNotify: Boolean = true)
}
