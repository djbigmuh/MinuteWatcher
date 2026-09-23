package com.example.minutewatcher.feature.settings.ui

/**
 * Result of widget settings save operation.
 *
 * Sealed interface guarantees exhaustive handling of all options
 * in when expression. Compiler guarantees all options are handled.
 */
sealed interface SaveResult {
    /**
     * Complete save success. All settings saved.
     *
     * **Important:** this is a one-shot event for Snackbar.
     * UI should display "Settings saved" message.
     * After displaying message, UI calls consumeSaveResult()
     * to reset saveResult to null.
     */
    data object Success : SaveResult

    /**
     * Partial success: general settings saved, but SIM-specific
     * settings skipped (simListLoadFailed = true).
     *
     * **Important:** this is a one-shot event for Snackbar.
     * UI should display message "General settings saved.
     * SIM settings skipped (SIM list unavailable)."
     */
    data class PartialSuccess(
        val skippedSimSettings: Boolean,
    ) : SaveResult

    /**
     * Partial failure: general settings saved successfully, but at least one
     * SIM-specific setting was not saved due to an error.
     *
     * **Important:** this is a one-shot event for Snackbar.
     */
    data class PartialFailure(
        val message: String,
    ) : SaveResult

    /**
     * Complete save failure. Settings not saved.
     *
     * **Important:** this is a one-shot event for Snackbar.
     */
    data class Failed(
        val message: String? = null,
    ) : SaveResult
}
