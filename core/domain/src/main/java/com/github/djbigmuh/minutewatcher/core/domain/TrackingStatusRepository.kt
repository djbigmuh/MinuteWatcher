package com.github.djbigmuh.minutewatcher.core.domain

import com.github.djbigmuh.minutewatcher.core.model.TrackingKey
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for observing and managing tracking status of all components.
 *
 * All methods are safe to call from any coroutine context.
 */
interface TrackingStatusRepository {
    /**
     * Attempts to recover tracking for a specific component.
     *
     * @param key The unique key of the component to recover.
     * @throws IOException if recovery fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun recoverTracking(key: TrackingKey)

    /**
     * Observes the status of all tracking components.
     */
    fun observeTrackingStatus(): Flow<Map<TrackingKey, TrackingStatus>>
}
