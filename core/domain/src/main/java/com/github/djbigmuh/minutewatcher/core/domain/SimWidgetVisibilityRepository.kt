package com.github.djbigmuh.minutewatcher.core.domain

import com.github.djbigmuh.minutewatcher.core.model.SimSettingsState
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for managing SIM visibility and settings for widgets.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Timeout bounding for repository operations is the responsibility of callers
 * (e.g. DataStoreRetryHelper, withTimeoutOrNull).
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
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun setSimSettingsBatch(settingsMap: Map<String, SimSettingsState>)

    /**
     * Returns a flow that emits whenever SIM widget settings or limit settings change.
     */
    fun observeSimSettings(): Flow<Unit>
}
