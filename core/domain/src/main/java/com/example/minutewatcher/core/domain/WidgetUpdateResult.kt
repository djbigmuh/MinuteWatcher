package com.example.minutewatcher.core.domain

/**
 * Result of a widget update operation.
 */
sealed class WidgetUpdateResult {
    /**
     * Update completed successfully.
     * @param wasCoalesced True if this call joined an already running update.
     */
    data class Success(
        val wasCoalesced: Boolean = false,
    ) : WidgetUpdateResult()

    /**
     * Update failed.
     * @param wasCoalesced True if this call joined an already running update that failed.
     * @param isRetryable True if the operation can be retried.
     */
    data class Failure(
        val wasCoalesced: Boolean = false,
        val isRetryable: Boolean = true,
    ) : WidgetUpdateResult()

    val isSuccess: Boolean get() = this is Success
}
