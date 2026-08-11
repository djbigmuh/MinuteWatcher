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
     * A save operation is already in progress.
     */
    data object AlreadyInProgress : SaveResult

    /**
     * The save operation failed.
     */
    data object Failed : SaveResult
}
