package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.WidgetSettingsState

interface WidgetSettingsLoader {
    suspend fun load(): WidgetSettingsState

    suspend fun save(
        state: WidgetSettingsState,
        skipSimSettings: Boolean = false,
    )
}
