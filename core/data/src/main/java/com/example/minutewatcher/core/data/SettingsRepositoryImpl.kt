package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl
    @Inject
    constructor(
        private val seedRepository: SimIdentitySeedRepository,
        @AppDataStore private val dataStore: DataStore<Preferences>,
        @com.example.minutewatcher.core.data.di.ApplicationScope private val scope: CoroutineScope,
    ) : SettingsRepository {
        private val appSettingsState =
            dataStore.data
                .map { prefs ->
                    AppSettings(
                        theme = AppTheme.fromValue(prefs[SettingsKeys.APP_THEME] ?: -1),
                        language = prefs[SettingsKeys.APP_LANGUAGE],
                        notifyLowMinutes = prefs[SettingsKeys.NOTIFY_LOW_MINUTES] ?: true,
                        notifyLowTraffic = prefs[SettingsKeys.NOTIFY_LOW_TRAFFIC] ?: true,
                        callTrackingEnabled = prefs[SettingsKeys.CALL_TRACKING_ENABLED] ?: true,
                        widgetLayoutMode = prefs[SettingsKeys.WIDGET_LAYOUT_MODE] ?: 2,
                        widgetSimArrangement = prefs[SettingsKeys.WIDGET_SIM_ARRANGEMENT] ?: 0,
                        widgetTransparency = prefs[SettingsKeys.WIDGET_TRANSPARENCY] ?: 50,
                        widgetFontSize = prefs[SettingsKeys.WIDGET_FONT_SIZE] ?: 2,
                        widgetBackgroundColor = prefs[SettingsKeys.WIDGET_BACKGROUND_COLOR] ?: Constants.UI.DEFAULT_BG_COLOR,
                        showSim = listOf(true, true), // Will be handled by SimRepository or updated later
                        showSimIcon = listOf(true, true),
                        showSimName = listOf(true, true),
                        showMinutes = listOf(true, true),
                        showMinutesLimit = listOf(false, false),
                        showTraffic = listOf(true, true),
                        showTrafficLimit = listOf(false, false),
                        migrationV1Completed = prefs[SettingsKeys.MIGRATION_V1_COMPLETED] ?: false,
                    )
                }.stateIn(
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
                        showSim = listOf(true, true),
                        showSimIcon = listOf(true, true),
                        showSimName = listOf(true, true),
                        showMinutes = listOf(true, true),
                        showMinutesLimit = listOf(false, false),
                        showTraffic = listOf(true, true),
                        showTrafficLimit = listOf(false, false),
                        migrationV1Completed = false,
                    ),
                )

        override fun getAppSettings(): Flow<AppSettings> = appSettingsState

        override fun observeAppSettings(): StateFlow<AppSettings> = appSettingsState

        override fun isMigrationV1Completed(): Flow<Boolean> = get(SettingsKeys.MIGRATION_V1_COMPLETED, false)

        override suspend fun setMigrationV1Completed(completed: Boolean) {
            dataStore.edit { it[SettingsKeys.MIGRATION_V1_COMPLETED] = completed }
        }

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

        override fun getLastDataSubId(): Flow<Int> = get(SettingsKeys.LAST_DATA_SUB_ID, -1)

        override suspend fun setLastDataSubId(subId: Int) {
            dataStore.edit { it[SettingsKeys.LAST_DATA_SUB_ID] = subId }
        }

        override fun getCallStartTime(subId: Int): Flow<Long> = get(SettingsKeys.callStartTimeKey(subId), 0L)

        override suspend fun setCallStartTime(
            subId: Int,
            time: Long,
        ) {
            dataStore.edit { it[SettingsKeys.callStartTimeKey(subId)] = time }
        }

        override fun getBootTime(subId: Int): Flow<Long> = get(SettingsKeys.bootTimeKey(subId), 0L)

        override suspend fun setBootTime(
            subId: Int,
            time: Long,
        ) {
            dataStore.edit { it[SettingsKeys.bootTimeKey(subId)] = time }
        }

        override fun getCallIdentity(subId: Int): Flow<String?> = dataStore.data.map { it[SettingsKeys.callIdentityKey(subId)] }

        override suspend fun setCallIdentity(
            subId: Int,
            identity: String?,
        ) {
            dataStore.edit {
                if (identity == null) {
                    it.remove(SettingsKeys.callIdentityKey(subId))
                } else {
                    it[SettingsKeys.callIdentityKey(subId)] = identity
                }
            }
        }

        override fun getSimIdentitySeed(): Flow<String> = seedRepository.getSeed()

        override suspend fun setSimIdentitySeed(seed: String) = seedRepository.setSeed(seed)

        override suspend fun updateSettings(block: suspend (Any) -> Unit) {
            dataStore.edit { block(it) }
        }
    }
