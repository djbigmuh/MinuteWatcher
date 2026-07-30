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
class AppSettingsRepositoryImpl @Inject constructor(
    @AppDataStore private val dataStore: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
) : AppSettingsRepository {

    private val appSettingsState =
        dataStore.data
            .map { prefs -> prefs.toAppSettings() }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                AppSettings(
                    theme = AppTheme.FOLLOW_SYSTEM,
                    language = null,
                    notifyLowMinutes = true,
                    notifyLowTraffic = true,
                    callTrackingEnabled = true,
                    widgetLayoutMode = 2,
                    widgetSimArrangement = 0,
                    widgetTransparency = 50,
                    widgetFontSize = 2,
                    widgetBackgroundColor = Constants.UI.DEFAULT_BG_COLOR,
                    migrationV1Completed = false,
                    onboardingCompleted = false,
                ),
            )

    override fun getAppSettings(): Flow<AppSettings> = appSettingsState

    override fun observeAppSettings(): StateFlow<AppSettings> = appSettingsState

    private fun <T> get(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

    override fun getAppTheme(): Flow<AppTheme> = get(SettingsKeys.APP_THEME, -1).map { AppTheme.fromValue(it) }

    override suspend fun setAppTheme(theme: AppTheme) {
        dataStore.edit { it[SettingsKeys.APP_THEME] = theme.value }
    }

    override fun getAppLanguage(): Flow<String?> = dataStore.data.map { it[SettingsKeys.APP_LANGUAGE] }

    override suspend fun setAppLanguage(language: String?) {
        dataStore.edit {
            if (language == null) {
                it.remove(SettingsKeys.APP_LANGUAGE)
            } else {
                it[SettingsKeys.APP_LANGUAGE] = language
            }
        }
    }

    override fun getWidgetLayoutMode(): Flow<Int> = get(SettingsKeys.WIDGET_LAYOUT_MODE, 2)

    override suspend fun setWidgetLayoutMode(mode: Int) {
        dataStore.edit { it[SettingsKeys.WIDGET_LAYOUT_MODE] = mode }
    }

    override fun getWidgetSimArrangement(): Flow<Int> = get(SettingsKeys.WIDGET_SIM_ARRANGEMENT, 0)

    override suspend fun setWidgetSimArrangement(arrangement: Int) {
        dataStore.edit { it[SettingsKeys.WIDGET_SIM_ARRANGEMENT] = arrangement }
    }

    override fun getWidgetTransparency(): Flow<Int> = get(SettingsKeys.WIDGET_TRANSPARENCY, 50)

    override suspend fun setWidgetTransparency(transparency: Int) {
        dataStore.edit { it[SettingsKeys.WIDGET_TRANSPARENCY] = transparency }
    }

    override fun getWidgetFontSize(): Flow<Int> = get(SettingsKeys.WIDGET_FONT_SIZE, 2)

    override suspend fun setWidgetFontSize(size: Int) {
        dataStore.edit { it[SettingsKeys.WIDGET_FONT_SIZE] = size }
    }

    override fun getWidgetBackgroundColor(): Flow<Int> = get(SettingsKeys.WIDGET_BACKGROUND_COLOR, Constants.UI.DEFAULT_BG_COLOR)

    override suspend fun setWidgetBackgroundColor(color: Int) {
        dataStore.edit { it[SettingsKeys.WIDGET_BACKGROUND_COLOR] = color }
    }

    override fun getNotifyLowMinutes(): Flow<Boolean> = get(SettingsKeys.NOTIFY_LOW_MINUTES, true)

    override suspend fun setNotifyLowMinutes(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.NOTIFY_LOW_MINUTES] = enabled }
    }

    override fun getNotifyLowTraffic(): Flow<Boolean> = get(SettingsKeys.NOTIFY_LOW_TRAFFIC, true)

    override suspend fun setNotifyLowTraffic(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.NOTIFY_LOW_TRAFFIC] = enabled }
    }

    override fun getCallTrackingEnabled(): Flow<Boolean> = get(SettingsKeys.CALL_TRACKING_ENABLED, true)

    override suspend fun setCallTrackingEnabled(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.CALL_TRACKING_ENABLED] = enabled }
    }

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
        }
    }

    private fun Preferences.toAppSettings(): AppSettings =
        AppSettings(
            theme = AppTheme.fromValue(this[SettingsKeys.APP_THEME] ?: -1),
            language = this[SettingsKeys.APP_LANGUAGE],
            notifyLowMinutes = this[SettingsKeys.NOTIFY_LOW_MINUTES] ?: true,
            notifyLowTraffic = this[SettingsKeys.NOTIFY_LOW_TRAFFIC] ?: true,
            callTrackingEnabled = this[SettingsKeys.CALL_TRACKING_ENABLED] ?: true,
            widgetLayoutMode = this[SettingsKeys.WIDGET_LAYOUT_MODE] ?: 2,
            widgetSimArrangement = this[SettingsKeys.WIDGET_SIM_ARRANGEMENT] ?: 0,
            widgetTransparency = this[SettingsKeys.WIDGET_TRANSPARENCY] ?: 50,
            widgetFontSize = this[SettingsKeys.WIDGET_FONT_SIZE] ?: 2,
            widgetBackgroundColor = this[SettingsKeys.WIDGET_BACKGROUND_COLOR] ?: Constants.UI.DEFAULT_BG_COLOR,
            migrationV1Completed = this[SettingsKeys.MIGRATION_V1_COMPLETED] ?: false,
            onboardingCompleted = this[SettingsKeys.ONBOARDING_COMPLETED] ?: false,
            notificationPermissionRequested = this[SettingsKeys.NOTIFICATION_PERMISSION_REQUESTED] ?: false,
            phonePermissionRequested = this[SettingsKeys.PHONE_PERMISSION_REQUESTED] ?: false,
        )
}
