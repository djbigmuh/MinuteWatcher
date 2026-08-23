package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [AppSettingsRepository] using [DataStore].
 *
 * This implementation is AOSP DataStore compliant and follows strict consistency rules
 * for atomic updates using [Mutex].
 * Safe to call from any coroutine context.
 */
@Singleton
class AppSettingsRepositoryImpl
    @Inject
    constructor(
        @AppDataStore private val dataStore: DataStore<Preferences>,
        @ApplicationScope private val scope: CoroutineScope,
    ) : AppSettingsRepository {
        // Ключи DataStore — деталь реализации. Публичный API репозитория не должен их раскрывать.
        // Это соответствует принципу Dependency Inversion.

        private val appSettingsState =
            dataStore.data
                .map { prefs -> prefs.toAppSettings() }
                .stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                    AppSettings(),
                )

        /**
         * Returns a flow of application settings.
         *
         * Safe to call from any coroutine context.
         * @throws IOException if DataStore reading fails.
         */
        override fun getAppSettings(): Flow<AppSettings> = dataStore.data.map { prefs -> prefs.toAppSettings() }

        /**
         * Returns a state flow of application settings.
         *
         * Initialized using [Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS] for efficient resource management.
         * Safe to call from any coroutine context.
         */
        override fun observeAppSettings(): StateFlow<AppSettings> = appSettingsState

        private val updateMutex = Mutex()

        /**
         * Atomically updates application settings using a transform function.
         *
         * Guaranteed to be atomic via [Mutex].
         * Safe to call from any coroutine context.
         * @throws IOException if DataStore write operation fails.
         * @throws CancellationException if the update process is cancelled.
         */
        override suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
            updateMutex.withLock {
                dataStore.edit { prefs ->
                    val current = prefs.toAppSettings()
                    val updated = transform(current)
                    applyUpdatedSettings(prefs, updated)
                }
            }
        }

        private fun applyUpdatedSettings(
            prefs: MutablePreferences,
            updated: AppSettings,
        ) {
            updateBasicSettings(prefs, updated)
            updateWidgetSettings(prefs, updated)
            updateSystemSettings(prefs, updated)
            updateTrackingSettings(prefs, updated)
        }

        private fun updateBasicSettings(
            prefs: MutablePreferences,
            updated: AppSettings,
        ) {
            prefs[DataStoreKeys.APP_THEME] = updated.theme.value
            val language = updated.language
            if (language == null) {
                prefs.remove(DataStoreKeys.APP_LANGUAGE)
            } else {
                prefs[DataStoreKeys.APP_LANGUAGE] = language
            }
        }

        private fun updateWidgetSettings(
            prefs: MutablePreferences,
            updated: AppSettings,
        ) {
            prefs[DataStoreKeys.WIDGET_LAYOUT_MODE] = updated.widgetLayoutMode
            prefs[DataStoreKeys.WIDGET_SIM_ARRANGEMENT] = updated.widgetSimArrangement
            prefs[DataStoreKeys.WIDGET_TRANSPARENCY] = updated.widgetTransparency
            prefs[DataStoreKeys.WIDGET_FONT_SIZE] = updated.widgetFontSize
            prefs[DataStoreKeys.WIDGET_BACKGROUND_COLOR] = updated.widgetBackgroundColor
            prefs[DataStoreKeys.WIDGET_LIMIT_EXCEEDED] = updated.widgetLimitExceeded
        }

        private fun updateSystemSettings(
            prefs: MutablePreferences,
            updated: AppSettings,
        ) {
            prefs[DataStoreKeys.MIGRATION_V1_COMPLETED] = updated.migrationV1Completed
            prefs[DataStoreKeys.ONBOARDING_COMPLETED] = updated.onboardingCompleted
            prefs[DataStoreKeys.NOTIFICATION_PERMISSION_REQUESTED] = updated.notificationPermissionRequested
            prefs[DataStoreKeys.WAS_PHONE_PERMISSION_REQUESTED_BEFORE] = updated.wasPhonePermissionRequestedBefore
            prefs[DataStoreKeys.PHONE_PERMISSION_REQUESTED] = updated.phonePermissionRequested
            prefs[DataStoreKeys.BENCHMARK_SIM_COUNT] = updated.benchmarkSimCount
        }

        private fun updateTrackingSettings(
            prefs: MutablePreferences,
            updated: AppSettings,
        ) {
            prefs[DataStoreKeys.NOTIFY_LOW_MINUTES] = updated.notifyLowMinutes
            prefs[DataStoreKeys.NOTIFY_LOW_TRAFFIC] = updated.notifyLowTraffic
            prefs[DataStoreKeys.INACCURATE_CALL_ATTRIBUTIONS_COUNT] = updated.inaccurateCallAttributionsCount
            prefs[DataStoreKeys.CALL_TRACKING_ENABLED] = updated.callTrackingEnabled
        }

        private fun Preferences.toAppSettings(): AppSettings =
            AppSettings()
                .mapBasicSettings(this)
                .mapWidgetSettings(this)
                .mapSystemSettings(this)
                .mapTrackingSettings(this)

        private fun AppSettings.mapBasicSettings(prefs: Preferences): AppSettings =
            copy(
                theme = AppTheme.fromValue(prefs[DataStoreKeys.APP_THEME] ?: -1),
                language = prefs[DataStoreKeys.APP_LANGUAGE],
            )

        private fun AppSettings.mapWidgetSettings(prefs: Preferences): AppSettings =
            copy(
                widgetLayoutMode = prefs[DataStoreKeys.WIDGET_LAYOUT_MODE] ?: widgetLayoutMode,
                widgetSimArrangement = prefs[DataStoreKeys.WIDGET_SIM_ARRANGEMENT] ?: widgetSimArrangement,
                widgetTransparency = prefs[DataStoreKeys.WIDGET_TRANSPARENCY] ?: widgetTransparency,
                widgetFontSize = prefs[DataStoreKeys.WIDGET_FONT_SIZE] ?: widgetFontSize,
                widgetBackgroundColor = prefs[DataStoreKeys.WIDGET_BACKGROUND_COLOR] ?: widgetBackgroundColor,
                widgetLimitExceeded = prefs[DataStoreKeys.WIDGET_LIMIT_EXCEEDED] ?: widgetLimitExceeded,
            )

        private fun AppSettings.mapSystemSettings(prefs: Preferences): AppSettings =
            copy(
                migrationV1Completed = prefs[DataStoreKeys.MIGRATION_V1_COMPLETED] ?: migrationV1Completed,
                onboardingCompleted = prefs[DataStoreKeys.ONBOARDING_COMPLETED] ?: onboardingCompleted,
                notificationPermissionRequested =
                    prefs[DataStoreKeys.NOTIFICATION_PERMISSION_REQUESTED] ?: notificationPermissionRequested,
                wasPhonePermissionRequestedBefore =
                    prefs[DataStoreKeys.WAS_PHONE_PERMISSION_REQUESTED_BEFORE] ?: wasPhonePermissionRequestedBefore,
                phonePermissionRequested = prefs[DataStoreKeys.PHONE_PERMISSION_REQUESTED] ?: phonePermissionRequested,
                benchmarkSimCount = prefs[DataStoreKeys.BENCHMARK_SIM_COUNT] ?: benchmarkSimCount,
            )

        private fun AppSettings.mapTrackingSettings(prefs: Preferences): AppSettings =
            copy(
                notifyLowMinutes = prefs[DataStoreKeys.NOTIFY_LOW_MINUTES] ?: notifyLowMinutes,
                notifyLowTraffic = prefs[DataStoreKeys.NOTIFY_LOW_TRAFFIC] ?: notifyLowTraffic,
                inaccurateCallAttributionsCount =
                    prefs[DataStoreKeys.INACCURATE_CALL_ATTRIBUTIONS_COUNT] ?: inaccurateCallAttributionsCount,
                callTrackingEnabled = prefs[DataStoreKeys.CALL_TRACKING_ENABLED] ?: callTrackingEnabled,
            )

        companion object {
            // No-op
        }
    }
