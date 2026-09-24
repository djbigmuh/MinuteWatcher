package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.AppDataStore
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.observeWithRetry
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.TrackingStatusMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [AppSettingsRepository] using [DataStore].
 *
 * This implementation is AOSP DataStore compliant and follows strict consistency rules
 * for atomic updates using [Mutex].
 * Safe to call from any coroutine context.
 *
 * NOTE: Suppressing TooManyFunctions as this repository manages multiple settings groups
 * with fine-grained update methods, adhering to AOSP DataStore best practices.
 */
@Singleton
@Suppress("TooManyFunctions")
class AppSettingsRepositoryImpl
    @Inject
    constructor(
        @AppDataStore private val dataStore: DataStore<Preferences>,
        @ApplicationScope private val scope: CoroutineScope,
    ) : AppSettingsRepository,
        TrackingStatusProvider {
        override val key: TrackingKey = TrackingKey.APP_SETTINGS

        private val statusMachine = TrackingStatusMachine()
        override val status: StateFlow<TrackingStatus> = statusMachine.status

        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            statusMachine.transition(newStatus, force)
        }

        private val appSettingsState: StateFlow<AppSettings> =
            dataStore.data
                .map { prefs -> prefs.toAppSettings() }
                .distinctUntilChanged()
                .observeWithRetry(
                    label = "DataStore observation for appSettingsState",
                    logThreshold = LOG_THRESHOLD,
                    onPersistentFailure = {
                        transitionStatus(
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.DATABASE_ERROR,
                                "Persistent DataStore error: settings observation degraded",
                            ),
                        )
                    },
                    onRecovered = {
                        transitionStatus(TrackingStatus.Active, force = true)
                    },
                    onSourceCompleted = {},
                ).stateIn(
                    scope,
                    SharingStarted.Eagerly, // Starts collection immediately to minimize time with default values.
                    AppSettings(),
                )

        /**
         * Returns a cold flow of application settings reading directly from DataStore.
         *
         * Each subscription reads fresh data directly from DataStore. Emits only values
         * loaded from DataStore (isLoaded = true). IOException from DataStore is propagated
         * to the collector. Single reads should be limited by a timeout on the caller's side.
         *
         * Safe to call from any coroutine context.
         */
        override fun getAppSettings(): Flow<AppSettings> =
            dataStore.data
                .map { prefs -> prefs.toAppSettings() }
                .distinctUntilChanged()

        /**
         * Returns a state flow of application settings.
         *
         * Initialized using [SharingStarted.Eagerly] to ensure the value is always fresh
         * and available immediately without "flashing" defaults upon subscription.
         *
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
                    val updated =
                        try {
                            transform(current)
                        } catch (e: Exception) {
                            AppLogger.e("updateAppSettings: transform failed", e)
                            throw e
                        }

                    if (current == updated) return@edit

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
                .let { mapBasicSettings(it) }
                .let { mapSystemSettings(it) }
                .let { mapTrackingSettings(it) }
                .let { mapWidgetSettings(it) }
                .copy(isLoaded = true)

        private fun Preferences.mapBasicSettings(current: AppSettings): AppSettings {
            val theme = AppTheme.fromValue(read(DataStoreKeys.APP_THEME, -1))
            val language = this[DataStoreKeys.APP_LANGUAGE]
            return current.copy(theme = theme, language = language)
        }

        private fun Preferences.mapSystemSettings(current: AppSettings): AppSettings =
            current.copy(
                migrationV1Completed = read(DataStoreKeys.MIGRATION_V1_COMPLETED, current.migrationV1Completed),
                onboardingCompleted = read(DataStoreKeys.ONBOARDING_COMPLETED, current.onboardingCompleted),
                notificationPermissionRequested =
                    read(DataStoreKeys.NOTIFICATION_PERMISSION_REQUESTED, current.notificationPermissionRequested),
                wasPhonePermissionRequestedBefore =
                    read(
                        DataStoreKeys.WAS_PHONE_PERMISSION_REQUESTED_BEFORE,
                        current.wasPhonePermissionRequestedBefore,
                    ),
                benchmarkSimCount = read(DataStoreKeys.BENCHMARK_SIM_COUNT, current.benchmarkSimCount),
            )

        private fun Preferences.mapTrackingSettings(current: AppSettings): AppSettings =
            current.copy(
                notifyLowMinutes = read(DataStoreKeys.NOTIFY_LOW_MINUTES, current.notifyLowMinutes),
                notifyLowTraffic = read(DataStoreKeys.NOTIFY_LOW_TRAFFIC, current.notifyLowTraffic),
                inaccurateCallAttributionsCount =
                    read(DataStoreKeys.INACCURATE_CALL_ATTRIBUTIONS_COUNT, current.inaccurateCallAttributionsCount),
                callTrackingEnabled = read(DataStoreKeys.CALL_TRACKING_ENABLED, current.callTrackingEnabled),
            )

        private fun Preferences.mapWidgetSettings(current: AppSettings): AppSettings =
            current.copy(
                widgetLayoutMode = read(DataStoreKeys.WIDGET_LAYOUT_MODE, current.widgetLayoutMode),
                widgetSimArrangement = read(DataStoreKeys.WIDGET_SIM_ARRANGEMENT, current.widgetSimArrangement),
                widgetTransparency = read(DataStoreKeys.WIDGET_TRANSPARENCY, current.widgetTransparency),
                widgetFontSize = read(DataStoreKeys.WIDGET_FONT_SIZE, current.widgetFontSize),
                widgetBackgroundColor = read(DataStoreKeys.WIDGET_BACKGROUND_COLOR, current.widgetBackgroundColor),
                widgetLimitExceeded = read(DataStoreKeys.WIDGET_LIMIT_EXCEEDED, current.widgetLimitExceeded),
            )

        private fun <T> Preferences.read(
            key: Preferences.Key<T>,
            default: T,
        ): T = this[key] ?: default

        companion object {
            private const val LOG_THRESHOLD = 5
        }
    }
