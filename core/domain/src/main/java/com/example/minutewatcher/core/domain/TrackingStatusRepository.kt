package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.Flow

interface TrackingStatusRepository {
    /**
     * Attempts to recover tracking for a specific component.
     */
    fun recoverTracking(key: String)

    /**
     * Observes the status of all tracking components.
     */
    fun observeTrackingStatus(): Flow<Map<String, TrackingStatus>>
}
