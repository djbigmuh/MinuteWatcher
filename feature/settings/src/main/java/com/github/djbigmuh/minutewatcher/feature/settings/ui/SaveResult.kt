package com.github.djbigmuh.minutewatcher.feature.settings.ui

/**
 * Reason for complete save operation failure.
 */
enum class SaveFailureReason {
    NOT_LOADED,
    IN_PROGRESS,
    TIMEOUT,
    GENERAL,
}

/**
 * Result of widget settings save operation.
 *
 * Sealed interface guarantees exhaustive handling of all options
 * in when expression. Compiler guarantees all options are handled.
 */
sealed interface SaveResult {
    /**
     * Complete save success. All settings saved.
     */
    data object Success : SaveResult

    /**
     * Partial success: general settings saved, but SIM-specific
     * settings skipped (simListLoadFailed = true).
     */
    data class PartialSuccess(
        val skippedSimSettings: Boolean,
    ) : SaveResult

    /**
     * Partial failure: general settings saved successfully, but at least one
     * SIM-specific setting was not saved due to an error.
     *
     * @property failedSlots 0-based indices of slots that failed to save.
     * An empty list means slot composition is unknown.
     */
    data class PartialFailure(
        val failedSlots: List<Int>,
    ) : SaveResult

    /**
     * Complete save failure. Settings not saved.
     *
     * @property reason Machine-readable reason for the failure.
     */
    data class Failed(
        val reason: SaveFailureReason,
    ) : SaveResult
}
