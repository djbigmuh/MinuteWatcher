package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.SimSettingsState
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for managing SIM visibility and settings for widgets.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Operations are typically bounded by SHORT_TIMEOUT_MS.
 */
interface SimWidgetVisibilityRepository {
    /**
     * Returns a flow of widget settings for a batch of identities.
     */
    fun getSimSettingsBatch(identities: List<String>): Flow<Map<String, SimSettingsState>>

    /**
     * Sets widget settings for a batch of identities.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setSimSettingsBatch(settingsMap: Map<String, SimSettingsState>)
}
