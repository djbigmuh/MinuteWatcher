package com.github.djbigmuh.minutewatcher.core.domain

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
     * @param reason The specific reason for the failure.
     */
    data class Failure(
        val wasCoalesced: Boolean = false,
        val isRetryable: Boolean = true,
        val reason: FailureReason = FailureReason.TRANSIENT,
    ) : WidgetUpdateResult()

    /**
     * Specific reasons for a widget update failure.
     */
    enum class FailureReason {
        /** Transient error, should be retried. */
        TRANSIENT,

        /** Widget size limit exceeded. */
        LIMIT_EXCEEDED,

        /** Terminal error that cannot be resolved without user intervention. */
        TERMINAL,
    }

    val isSuccess: Boolean get() = this is Success
}
