package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
        private val migrationFlagsRepository: MigrationFlagsRepository,
        private val settingsUseCase: SettingsUseCase,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        val isInitialSetupCompleted: Flow<Boolean> = migrationFlagsRepository.isInitialSetupCompleted()

        /**
         * Returns a state flow of application settings.
         * Uses the repository's hot flow directly.
         */
        val appSettings: StateFlow<AppSettings> =
            appSettingsRepository.observeAppSettings()

        /**
         * Cold flows for theme and language.
         * Should be collected in the UI scope (e.g., via viewModelScope.stateIn).
         */
        val appTheme: Flow<AppTheme> = settingsUseCase.appTheme
        val appLanguage: Flow<String?> = settingsUseCase.appLanguage

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
         * This is the canonical way to manually clear the widget error.
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
                appSettingsRepository.updateAppSettings {
                    it.copy(
                        wasPhonePermissionRequestedBefore = true,
                        phonePermissionRequested = true,
                    )
                }
            }
        }
    }
