package com.example.minutewatcher.feature.settings.ui

/**
 * Represent the result of a save operation in the widget settings.
 */
sealed interface SaveResult {
    /**
     * Settings were saved successfully.
     */
    data object Success : SaveResult

    /**
     * Partial success: general settings saved, but SIM-specific settings were skipped.
     * This occurs when the SIM list is unavailable (e.g., due to storage degradation
     * or custom firmware issues) to prevent data loss.
     *
     * @param skippedSimSettings True if SIM-specific settings were skipped.
     */
    data class PartialSuccess(
        val skippedSimSettings: Boolean,
    ) : SaveResult

    /**
     * A save operation is already in progress.
     */
    data object AlreadyInProgress : SaveResult

    /**
     * The save operation failed.
     */
    data object Failed : SaveResult
}
