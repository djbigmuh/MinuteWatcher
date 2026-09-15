package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for application settings.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices, ensuring atomic operations.
 */
interface AppSettingsRepository {
    /**
     * Returns a flow of application settings.
     */
    fun getAppSettings(): Flow<AppSettings>

    /**
     * Returns the current application settings once.
     */
    suspend fun getAppSettingsOnce(): AppSettings = getAppSettings().first()

    /**
     * Observes application settings as a StateFlow.
     */
    fun observeAppSettings(): StateFlow<AppSettings>

    /**
     * Updates application settings atomically.
     *
     * @param transform Transformation function for settings.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings)
}
