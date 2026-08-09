package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.data.di.AppDataStore
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsRepositoryImpl
    @Inject
    constructor(
        @AppDataStore private val dataStore: DataStore<Preferences>,
        @ApplicationScope private val scope: CoroutineScope,
    ) : AppSettingsRepository {
        private val appSettingsState =
            dataStore.data
                .map { prefs -> prefs.toAppSettings() }
                .stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                    AppSettings(),
                )

        override fun getAppSettings(): Flow<AppSettings> = dataStore.data.map { prefs -> prefs.toAppSettings() }

        override fun observeAppSettings(): StateFlow<AppSettings> = appSettingsState

        override suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
            dataStore.edit { prefs ->
                val current = prefs.toAppSettings()
                val updated = transform(current)

                updateBasicSettings(prefs, updated)
                updateWidgetSettings(prefs, updated)
                updateSystemSettings(prefs, updated)
                updateTrackingSettings(prefs, updated)
            }
        }

        private fun updateBasicSettings(
            prefs: MutablePreferences,
            updated: AppSettings,
        ) {
            prefs[SettingsKeys.APP_THEME] = updated.theme.value
            val language = updated.language
            if (language == null) {
                prefs.remove(SettingsKeys.APP_LANGUAGE)
            } else {
                prefs[SettingsKeys.APP_LANGUAGE] = language
            }
        }

        private fun updateWidgetSettings(
            prefs: MutablePreferences,
            updated: AppSettings,
        ) {
            prefs[SettingsKeys.WIDGET_LAYOUT_MODE] = updated.widgetLayoutMode
            prefs[SettingsKeys.WIDGET_SIM_ARRANGEMENT] = updated.widgetSimArrangement
            prefs[SettingsKeys.WIDGET_TRANSPARENCY] = updated.widgetTransparency
            prefs[SettingsKeys.WIDGET_FONT_SIZE] = updated.widgetFontSize
            prefs[SettingsKeys.WIDGET_BACKGROUND_COLOR] = updated.widgetBackgroundColor
            prefs[SettingsKeys.WIDGET_LIMIT_EXCEEDED] = updated.widgetLimitExceeded
        }

        private fun updateSystemSettings(
            prefs: MutablePreferences,
            updated: AppSettings,
        ) {
            prefs[SettingsKeys.MIGRATION_V1_COMPLETED] = updated.migrationV1Completed
            prefs[SettingsKeys.ONBOARDING_COMPLETED] = updated.onboardingCompleted
            prefs[SettingsKeys.NOTIFICATION_PERMISSION_REQUESTED] = updated.notificationPermissionRequested
            prefs[SettingsKeys.PHONE_PERMISSION_REQUESTED] = updated.phonePermissionRequested
            prefs[SettingsKeys.BENCHMARK_SIM_COUNT] = updated.benchmarkSimCount
        }

        private fun updateTrackingSettings(
            prefs: MutablePreferences,
            updated: AppSettings,
        ) {
            prefs[SettingsKeys.NOTIFY_LOW_MINUTES] = updated.notifyLowMinutes
            prefs[SettingsKeys.NOTIFY_LOW_TRAFFIC] = updated.notifyLowTraffic
            prefs[SettingsKeys.CALL_TRACKING_ENABLED] = updated.callTrackingEnabled
            prefs[SettingsKeys.INACCURATE_CALL_ATTRIBUTIONS_COUNT] = updated.inaccurateCallAttributionsCount
        }

        private fun Preferences.toAppSettings(): AppSettings =
            AppSettings(
                theme = AppTheme.fromValue(this[SettingsKeys.APP_THEME] ?: -1),
                language = this[SettingsKeys.APP_LANGUAGE],
                notifyLowMinutes = this[SettingsKeys.NOTIFY_LOW_MINUTES] ?: DEFAULT_SETTINGS.notifyLowMinutes,
                notifyLowTraffic = this[SettingsKeys.NOTIFY_LOW_TRAFFIC] ?: DEFAULT_SETTINGS.notifyLowTraffic,
                callTrackingEnabled = this[SettingsKeys.CALL_TRACKING_ENABLED] ?: DEFAULT_SETTINGS.callTrackingEnabled,
                widgetLayoutMode = this[SettingsKeys.WIDGET_LAYOUT_MODE] ?: DEFAULT_SETTINGS.widgetLayoutMode,
                widgetSimArrangement =
                    this[SettingsKeys.WIDGET_SIM_ARRANGEMENT] ?: DEFAULT_SETTINGS.widgetSimArrangement,
                widgetTransparency = this[SettingsKeys.WIDGET_TRANSPARENCY] ?: DEFAULT_SETTINGS.widgetTransparency,
                widgetFontSize = this[SettingsKeys.WIDGET_FONT_SIZE] ?: DEFAULT_SETTINGS.widgetFontSize,
                widgetBackgroundColor =
                    this[SettingsKeys.WIDGET_BACKGROUND_COLOR] ?: DEFAULT_SETTINGS.widgetBackgroundColor,
                migrationV1Completed =
                    this[SettingsKeys.MIGRATION_V1_COMPLETED] ?: DEFAULT_SETTINGS.migrationV1Completed,
                onboardingCompleted = this[SettingsKeys.ONBOARDING_COMPLETED] ?: DEFAULT_SETTINGS.onboardingCompleted,
                notificationPermissionRequested =
                    this[SettingsKeys.NOTIFICATION_PERMISSION_REQUESTED]
                        ?: DEFAULT_SETTINGS.notificationPermissionRequested,
                phonePermissionRequested =
                    this[SettingsKeys.PHONE_PERMISSION_REQUESTED] ?: DEFAULT_SETTINGS.phonePermissionRequested,
                benchmarkSimCount = this[SettingsKeys.BENCHMARK_SIM_COUNT] ?: DEFAULT_SETTINGS.benchmarkSimCount,
                widgetLimitExceeded = this[SettingsKeys.WIDGET_LIMIT_EXCEEDED] ?: DEFAULT_SETTINGS.widgetLimitExceeded,
                inaccurateCallAttributionsCount =
                    this[SettingsKeys.INACCURATE_CALL_ATTRIBUTIONS_COUNT]
                        ?: DEFAULT_SETTINGS.inaccurateCallAttributionsCount,
            )

        companion object {
            private val DEFAULT_SETTINGS = AppSettings()
        }
    }
