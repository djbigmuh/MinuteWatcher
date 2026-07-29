package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsUseCase
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) {
        val appTheme: Flow<AppTheme> = appSettingsRepository.getAppTheme()
        val appLanguage: Flow<String?> = appSettingsRepository.getAppLanguage()

        suspend fun setTheme(theme: AppTheme) {
            appSettingsRepository.setAppTheme(theme)
        }

        suspend fun setLanguage(language: String?) {
            appSettingsRepository.setAppLanguage(language)
        }
    }
