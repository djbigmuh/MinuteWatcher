package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsUseCase
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) {
        val appTheme: Flow<AppTheme> = appSettingsRepository.getAppSettings().map { it.theme }
        val appLanguage: Flow<String?> = appSettingsRepository.getAppSettings().map { it.language }

        suspend fun setTheme(theme: AppTheme) {
            appSettingsRepository.updateAppSettings { it.copy(theme = theme) }
        }

        suspend fun setLanguage(language: String?) {
            appSettingsRepository.updateAppSettings { it.copy(language = language) }
        }
    }
