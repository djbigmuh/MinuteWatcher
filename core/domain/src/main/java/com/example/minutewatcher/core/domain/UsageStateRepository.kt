package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface RefreshResult {
    val snapshot: GlobalSnapshot

    data class Success(override val snapshot: GlobalSnapshot) : RefreshResult
    data class Timeout(override val snapshot: GlobalSnapshot) : RefreshResult
}

interface UsageStateRepository {
    /**
     * Наблюдает за данными всех активных SIM-карт.
     */
    fun observeSimUsages(): Flow<List<SimUsage>>

    /**
     * Наблюдает за моделями для UI.
     */
    fun observeSimCardUiModels(): Flow<List<SimCardUiModel>>

    /**
     * Возвращает текущий снимок состояния или наблюдает за ним.
     */
    fun observeSnapshot(): StateFlow<GlobalSnapshot>

    /**
     * Принудительно обновляет данные и возвращает результат.
     */
    suspend fun refresh(): RefreshResult
}
