package host.serv.djbigmuh.minutewatcher.feature.main.controller

import host.serv.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SettingsUseCase
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.AppTheme
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
        migrationFlagsRepository: MigrationFlagsRepository,
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
            launchSafely("setTheme") {
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
            launchSafely("setLanguage") {
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
            launchSafely("completeOnboarding") {
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
            launchSafely("dismissWidgetError") {
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
            launchSafely("dismissInaccurateCalls") {
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
            launchSafely("setNotificationPermissionRequested") {
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
            launchSafely("setPhonePermissionRequested") {
                appSettingsRepository.updateAppSettings {
                    it.copy(
                        wasPhonePermissionRequestedBefore = true,
                    )
                }
            }
        }

        /**
         * Sets whether phone permission is permanently denied.
         *
         * @param denied `true` if permanently denied, `false` otherwise.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun setPhonePermissionPermanentlyDenied(denied: Boolean) {
            launchSafely("setPhonePermissionPermanentlyDenied") {
                appSettingsRepository.updateAppSettings {
                    it.copy(phonePermissionPermanentlyDenied = denied)
                }
            }
        }

        /**
         * Sets whether notification permission is permanently denied.
         *
         * @param denied `true` if permanently denied, `false` otherwise.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) {
            launchSafely("setNotificationPermissionPermanentlyDenied") {
                appSettingsRepository.updateAppSettings {
                    it.copy(notificationPermissionPermanentlyDenied = denied)
                }
            }
        }

        /**
         * Marks that the usage access dialog has been shown.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun markUsageAccessDialogShown() {
            launchSafely("markUsageAccessDialogShown") {
                appSettingsRepository.updateAppSettings { it.copy(usageAccessDialogShown = true) }
            }
        }

        private fun launchSafely(
            operationName: String,
            block: suspend () -> Unit,
        ) {
            scope.launch {
                @Suppress("TooGenericExceptionCaught")
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for settings task.
                    AppLogger.e("MainSettingsController: $operationName failed", e)
                }
            }
        }
    }
