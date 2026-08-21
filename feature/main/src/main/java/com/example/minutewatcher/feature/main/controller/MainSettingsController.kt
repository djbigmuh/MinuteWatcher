package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MainSettingsController manages application settings (theme, language, onboarding) for the main screen.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [CancellationException]. Internal repository errors are logged.
 * **AOSP DataStore:** Fully compliant with AOSP DataStore semantics for atomic updates.
 *
 * @property appSettingsRepository Repository for application settings.
 * @property settingsUseCase Use case for application settings.
 * @property scope Application-wide coroutine scope for async operations.
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

        /**
         * Sets the application theme.
         *
         * @param theme The theme to apply.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun setTheme(theme: AppTheme) {
            scope.launch {
                settingsUseCase.setTheme(theme)
            }
        }

        /**
         * Sets the application language.
         *
         * @param language The language code to apply, or null for system default.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun setLanguage(language: String?) {
            scope.launch {
                settingsUseCase.setLanguage(language)
            }
        }

        /**
         * Marks the onboarding process as completed.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun completeOnboarding() {
            scope.launch {
                appSettingsRepository.updateAppSettings { it.copy(onboardingCompleted = true) }
            }
        }

        /**
         * Dismisses the widget limit exceeded error flag.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun dismissWidgetError() {
            scope.launch {
                appSettingsRepository.updateAppSettings { it.copy(widgetLimitExceeded = false) }
            }
        }

        /**
         * Resets the inaccurate call attributions counter.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun dismissInaccurateCalls() {
            scope.launch {
                appSettingsRepository.updateAppSettings { it.copy(inaccurateCallAttributionsCount = 0) }
            }
        }

        /**
         * Marks that notification permission has been requested.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun setNotificationPermissionRequested() {
            scope.launch {
                appSettingsRepository.updateAppSettings { it.copy(notificationPermissionRequested = true) }
            }
        }

        /**
         * Marks that phone permission has been requested.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun setPhonePermissionRequested() {
            scope.launch {
                appSettingsRepository.updateAppSettings { it.copy(phonePermissionRequested = true) }
            }
        }
    }
