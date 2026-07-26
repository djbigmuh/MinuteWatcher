package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface UsageStateRepository {
    /**
     * Observes the current usage stats for all active SIM cards.
     */
    fun observeSimUsages(): Flow<List<SimUsage>>

    /**
     * Observes the UI models for all active SIM cards.
     */
    fun observeSimCardUiModels(): Flow<List<SimCardUiModel>>

    /**
     * Observes the global snapshot of the system state.
     */
    fun observeSnapshot(): StateFlow<GlobalSnapshot>

    /**
     * Observes the combined tracking status from all background components.
     */
    fun observeTrackingStatus(): Flow<Map<String, com.example.minutewatcher.core.model.TrackingStatus>>

    /**
     * Forces a refresh of the current usage data.
     */
    suspend fun refresh()
}
