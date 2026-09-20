package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.WidgetSettingsState
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Loader for widget-related settings.
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
