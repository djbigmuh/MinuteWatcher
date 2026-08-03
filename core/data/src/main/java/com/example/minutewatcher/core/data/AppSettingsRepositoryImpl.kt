package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
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
                    SharingStarted.WhileSubscribed(Constants.Flow.STATE_FLOW_STOP_TIMEOUT_MS),
                    AppSettings(),
                )

        override fun getAppSettings(): Flow<AppSettings> = dataStore.data.map { prefs -> prefs.toAppSettings() }

        override fun observeAppSettings(): StateFlow<AppSettings> = appSettingsState

        override suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
            dataStore.edit { prefs ->
                val current = prefs.toAppSettings()
                val updated = transform(current)

                prefs[SettingsKeys.APP_THEME] = updated.theme.value
                val language = updated.language
                if (language == null) {
                    prefs.remove(SettingsKeys.APP_LANGUAGE)
                } else {
                    prefs[SettingsKeys.APP_LANGUAGE] = language
                }
                prefs[SettingsKeys.NOTIFY_LOW_MINUTES] = updated.notifyLowMinutes
                prefs[SettingsKeys.NOTIFY_LOW_TRAFFIC] = updated.notifyLowTraffic
                prefs[SettingsKeys.CALL_TRACKING_ENABLED] = updated.callTrackingEnabled
                prefs[SettingsKeys.WIDGET_LAYOUT_MODE] = updated.widgetLayoutMode
                prefs[SettingsKeys.WIDGET_SIM_ARRANGEMENT] = updated.widgetSimArrangement
                prefs[SettingsKeys.WIDGET_TRANSPARENCY] = updated.widgetTransparency
                prefs[SettingsKeys.WIDGET_FONT_SIZE] = updated.widgetFontSize
                prefs[SettingsKeys.WIDGET_BACKGROUND_COLOR] = updated.widgetBackgroundColor
                prefs[SettingsKeys.MIGRATION_V1_COMPLETED] = updated.migrationV1Completed
                prefs[SettingsKeys.ONBOARDING_COMPLETED] = updated.onboardingCompleted
                prefs[SettingsKeys.NOTIFICATION_PERMISSION_REQUESTED] = updated.notificationPermissionRequested
                prefs[SettingsKeys.PHONE_PERMISSION_REQUESTED] = updated.phonePermissionRequested
                prefs[SettingsKeys.BENCHMARK_SIM_COUNT] = updated.benchmarkSimCount
                prefs[SettingsKeys.WIDGET_LIMIT_EXCEEDED] = updated.widgetLimitExceeded
                prefs[SettingsKeys.INACCURATE_CALL_ATTRIBUTIONS_COUNT] = updated.inaccurateCallAttributionsCount
            }
        }

        private fun Preferences.toAppSettings(): AppSettings {
            val default = AppSettings()
            return AppSettings(
                theme = AppTheme.fromValue(this[SettingsKeys.APP_THEME] ?: -1),
                language = this[SettingsKeys.APP_LANGUAGE],
                notifyLowMinutes = this[SettingsKeys.NOTIFY_LOW_MINUTES] ?: default.notifyLowMinutes,
                notifyLowTraffic = this[SettingsKeys.NOTIFY_LOW_TRAFFIC] ?: default.notifyLowTraffic,
                callTrackingEnabled = this[SettingsKeys.CALL_TRACKING_ENABLED] ?: default.callTrackingEnabled,
                widgetLayoutMode = this[SettingsKeys.WIDGET_LAYOUT_MODE] ?: default.widgetLayoutMode,
                widgetSimArrangement = this[SettingsKeys.WIDGET_SIM_ARRANGEMENT] ?: default.widgetSimArrangement,
                widgetTransparency = this[SettingsKeys.WIDGET_TRANSPARENCY] ?: default.widgetTransparency,
                widgetFontSize = this[SettingsKeys.WIDGET_FONT_SIZE] ?: default.widgetFontSize,
                widgetBackgroundColor = this[SettingsKeys.WIDGET_BACKGROUND_COLOR] ?: default.widgetBackgroundColor,
                migrationV1Completed = this[SettingsKeys.MIGRATION_V1_COMPLETED] ?: default.migrationV1Completed,
                onboardingCompleted = this[SettingsKeys.ONBOARDING_COMPLETED] ?: default.onboardingCompleted,
                notificationPermissionRequested =
                    this[SettingsKeys.NOTIFICATION_PERMISSION_REQUESTED]
                        ?: default.notificationPermissionRequested,
                phonePermissionRequested =
                    this[SettingsKeys.PHONE_PERMISSION_REQUESTED] ?: default.phonePermissionRequested,
                benchmarkSimCount = this[SettingsKeys.BENCHMARK_SIM_COUNT] ?: default.benchmarkSimCount,
                widgetLimitExceeded = this[SettingsKeys.WIDGET_LIMIT_EXCEEDED] ?: default.widgetLimitExceeded,
                inaccurateCallAttributionsCount =
                    this[SettingsKeys.INACCURATE_CALL_ATTRIBUTIONS_COUNT]
                        ?: default.inaccurateCallAttributionsCount,
            )
        }
    }
