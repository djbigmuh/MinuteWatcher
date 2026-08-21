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
import kotlinx.coroutines.flow.first
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
                val current = dataStore.data.first().toAppSettings()
                val updated = transform(current)
                dataStore.edit { prefs -> applyUpdatedSettings(prefs, updated) }
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

        // @Suppress("CyclomaticComplexMethod") is required because mapping many DataStore fields
        // to AppSettings results in many decision points (elvis operators), exceeding the default limit.
        @Suppress("CyclomaticComplexMethod", "detekt.CyclomaticComplexMethod")
        private fun Preferences.toAppSettings(): AppSettings =
            AppSettings(
                theme = AppTheme.fromValue(this[DataStoreKeys.APP_THEME] ?: -1),
                language = this[DataStoreKeys.APP_LANGUAGE],
                notifyLowMinutes = this[DataStoreKeys.NOTIFY_LOW_MINUTES] ?: DEFAULT_SETTINGS.notifyLowMinutes,
                notifyLowTraffic = this[DataStoreKeys.NOTIFY_LOW_TRAFFIC] ?: DEFAULT_SETTINGS.notifyLowTraffic,
                widgetLayoutMode = this[DataStoreKeys.WIDGET_LAYOUT_MODE] ?: DEFAULT_SETTINGS.widgetLayoutMode,
                widgetSimArrangement =
                    this[DataStoreKeys.WIDGET_SIM_ARRANGEMENT] ?: DEFAULT_SETTINGS.widgetSimArrangement,
                widgetTransparency = this[DataStoreKeys.WIDGET_TRANSPARENCY] ?: DEFAULT_SETTINGS.widgetTransparency,
                widgetFontSize = this[DataStoreKeys.WIDGET_FONT_SIZE] ?: DEFAULT_SETTINGS.widgetFontSize,
                widgetBackgroundColor =
                    this[DataStoreKeys.WIDGET_BACKGROUND_COLOR] ?: DEFAULT_SETTINGS.widgetBackgroundColor,
                migrationV1Completed =
                    this[DataStoreKeys.MIGRATION_V1_COMPLETED] ?: DEFAULT_SETTINGS.migrationV1Completed,
                onboardingCompleted = this[DataStoreKeys.ONBOARDING_COMPLETED] ?: DEFAULT_SETTINGS.onboardingCompleted,
                notificationPermissionRequested =
                    this[DataStoreKeys.NOTIFICATION_PERMISSION_REQUESTED]
                        ?: DEFAULT_SETTINGS.notificationPermissionRequested,
                phonePermissionRequested =
                    this[DataStoreKeys.PHONE_PERMISSION_REQUESTED] ?: DEFAULT_SETTINGS.phonePermissionRequested,
                benchmarkSimCount = this[DataStoreKeys.BENCHMARK_SIM_COUNT] ?: DEFAULT_SETTINGS.benchmarkSimCount,
                widgetLimitExceeded = this[DataStoreKeys.WIDGET_LIMIT_EXCEEDED] ?: DEFAULT_SETTINGS.widgetLimitExceeded,
                inaccurateCallAttributionsCount =
                    this[DataStoreKeys.INACCURATE_CALL_ATTRIBUTIONS_COUNT]
                        ?: DEFAULT_SETTINGS.inaccurateCallAttributionsCount,
                callTrackingEnabled = this[DataStoreKeys.CALL_TRACKING_ENABLED] ?: DEFAULT_SETTINGS.callTrackingEnabled,
            )

        companion object {
            private val DEFAULT_SETTINGS = AppSettings()
        }
    }
