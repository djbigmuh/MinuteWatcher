package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.WidgetSettingsState

/**
 * Результат сохранения настроек виджета.
 */
sealed interface WidgetSaveResult {
    /**
     * Все запрошенные настройки (общие и SIM-специфичные) успешно сохранены.
     */
    data object Success : WidgetSaveResult

    /**
     * Частичный успех: общие настройки сохранены, но часть или все SIM-специфичные
     * настройки не были сохранены (например, из-за недоступности seed или отсутствия SIM).
     */
    data object PartialSuccess : WidgetSaveResult
}

interface WidgetSettingsLoader {
    suspend fun load(): WidgetSettingsState

    suspend fun save(
        state: WidgetSettingsState,
        skipSimSettings: Boolean = false,
    ): WidgetSaveResult

    /**
     * Сохраняет только общие настройки виджета.
     */
    suspend fun saveGeneralSettings(state: WidgetSettingsState)

    /**
     * Сохраняет только SIM-специфичные настройки.
     * @return true если все SIM успешно сохранены.
     */
    suspend fun saveSimSettings(state: WidgetSettingsState): Boolean
}
