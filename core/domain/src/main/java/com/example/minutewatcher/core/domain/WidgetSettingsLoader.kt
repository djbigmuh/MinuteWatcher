package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.WidgetSettingsState
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Result of saving widget settings.
 */
sealed interface WidgetSaveResult {
    /**
     * All requested settings (general and SIM-specific) were successfully saved.
     */
    data object Success : WidgetSaveResult

    /**
     * Partial success: general settings saved, but some SIM settings failed.
     */
    data object PartialSuccess : WidgetSaveResult

    /**
     * Saving failed completely.
     */
    data object Failed : WidgetSaveResult
}

/**
 * Loader and saver for widget-related settings.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Operations are typically bounded by SHORT_TIMEOUT_MS.
 */
interface WidgetSettingsLoader {
    /**
     * Loads the current widget settings state.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun load(): WidgetSettingsState

    /**
     * Saves the widget settings state.
     *
     * @param state The state to save.
     * @param skipSimSettings Whether to skip saving SIM-specific settings.
     * @return [WidgetSaveResult] indicating the outcome.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun save(
        state: WidgetSettingsState,
        skipSimSettings: Boolean = false,
    ): WidgetSaveResult

    /**
     * Saves only the general widget settings.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun saveGeneralSettings(state: WidgetSettingsState)

    /**
     * Saves only the SIM-specific widget settings.
     *
     * @return List of slot indices for which settings failed to save. Empty if all succeeded.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun saveSimSettings(state: WidgetSettingsState): List<Int>
}
