package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.SimSettingsState
import kotlinx.coroutines.flow.Flow

interface SimWidgetVisibilityRepository {
    fun getSimSettings(identity: String): Flow<SimSettingsState>

    fun getSimSettingsBatch(identities: List<String>): Flow<Map<String, SimSettingsState>>

    suspend fun setSimSettingsBatch(settingsMap: Map<String, SimSettingsState>)

    suspend fun clearOrphanedSettings(activeIdentities: List<String>)
}
