package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Контроллер настроек главного экрана.
 * Управляет настройками приложения (тема, язык, онбординг).
 *
 * @property appSettingsRepository Репозиторий настроек приложения.
 * @property settingsUseCase Use case настроек.
 * @property scope Scope для корутин.
 */
class MainSettingsController
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val settingsUseCase: SettingsUseCase,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        val appSettings: StateFlow<com.example.minutewatcher.core.model.AppSettings> =
            appSettingsRepository
                .observeAppSettings()
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue =
                        com.example.minutewatcher.core.model
                            .AppSettings(),
                )

        val appTheme: StateFlow<AppTheme> =
            settingsUseCase.appTheme
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = AppTheme.FOLLOW_SYSTEM,
                )

        val appLanguage: StateFlow<String?> =
            settingsUseCase.appLanguage
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = null,
                )

        fun setTheme(theme: AppTheme) {
            scope.launch {
                settingsUseCase.setTheme(theme)
            }
        }

        fun setLanguage(language: String?) {
            scope.launch {
                settingsUseCase.setLanguage(language)
            }
        }

        fun completeOnboarding() {
            scope.launch {
                appSettingsRepository.updateAppSettings { it.copy(onboardingCompleted = true) }
            }
        }

        fun dismissWidgetError() {
            scope.launch {
                appSettingsRepository.updateAppSettings { it.copy(widgetLimitExceeded = false) }
            }
        }

        fun dismissInaccurateCalls() {
            scope.launch {
                appSettingsRepository.updateAppSettings { it.copy(inaccurateCallAttributionsCount = 0) }
            }
        }

        fun setNotificationPermissionRequested() {
            scope.launch {
                appSettingsRepository.updateAppSettings { it.copy(notificationPermissionRequested = true) }
            }
        }

        fun setPhonePermissionRequested() {
            scope.launch {
                appSettingsRepository.updateAppSettings { it.copy(phonePermissionRequested = true) }
            }
        }
    }
