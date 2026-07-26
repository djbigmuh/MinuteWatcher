package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsUseCase
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val simRepository: SimRepository,
    ) {
        val appTheme: Flow<AppTheme> = settingsRepository.getAppTheme()
        val appLanguage: Flow<String?> = settingsRepository.getAppLanguage()

        suspend fun setTheme(theme: AppTheme) {
            settingsRepository.setAppTheme(theme)
        }

        suspend fun setLanguage(language: String?) {
            settingsRepository.setAppLanguage(language)
        }

        suspend fun getSimOperator(slot: Int): String {
            val identity = simRepository.getIdentity(slot)
            return simRepository.getSimOperator(identity).first()
        }
    }
