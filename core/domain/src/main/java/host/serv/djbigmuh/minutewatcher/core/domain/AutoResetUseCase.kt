package host.serv.djbigmuh.minutewatcher.core.domain

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
     * @return `true` if all SIMs were successfully processed, `false` if any partial failure occurred.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun performAutoReset(shouldNotify: Boolean = true): Boolean
}
