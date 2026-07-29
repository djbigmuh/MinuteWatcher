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
        private val appSettingsRepository: AppSettingsRepository,
        private val simIdentityRepository: SimIdentityRepository,
    ) {
        val appTheme: Flow<AppTheme> = appSettingsRepository.getAppTheme()
        val appLanguage: Flow<String?> = appSettingsRepository.getAppLanguage()

        suspend fun setTheme(theme: AppTheme) {
            appSettingsRepository.setAppTheme(theme)
        }

        suspend fun setLanguage(language: String?) {
            appSettingsRepository.setAppLanguage(language)
        }

        suspend fun getSimOperator(slot: Int): String {
            val identity = simIdentityRepository.getIdentity(slot)
            return simIdentityRepository.getSimOperator(identity).first()
        }
    }
