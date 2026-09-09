package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.AppDataStore
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

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

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private fun updateStatus(
            status: TrackingStatus,
            force: Boolean,
        ) {
            _status.update { current ->
                val currentPrio = getStatusPriority(current)
                val newPrio = getStatusPriority(status)

                if (force || newPrio >= currentPrio) status else current
            }
        }

        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            updateStatus(newStatus, force)
        }

        private fun getStatusPriority(status: TrackingStatus): Int =
            when (status) {
                is TrackingStatus.Error ->
                    when (status.type) {
                        TrackingStatus.ErrorType.PERMISSION_MISSING -> TrackingStatus.PRIORITY_PERMISSION_MISSING
                        TrackingStatus.ErrorType.DATABASE_ERROR -> TrackingStatus.PRIORITY_DATABASE_ERROR
                        else -> TrackingStatus.PRIORITY_TRANSIENT_ERROR
                    }

                is TrackingStatus.Active, is TrackingStatus.Idle -> TrackingStatus.PRIORITY_HEALTHY
                else -> 0
            }

        private val appSettingsState: StateFlow<AppSettings> =
            flow {
                var consecutiveErrors = 0
                while (true) {
                    try {
                        dataStore.data
                            .map { prefs ->
                                if (consecutiveErrors > 0) {
                                    AppLogger.d(
                                        "DataStore observation for appSettingsState recovered " +
                                            "after $consecutiveErrors errors",
                                    )
                                    consecutiveErrors = 0
                                    transitionStatus(TrackingStatus.Active, force = true)
                                }
                                prefs.toAppSettings()
                            }.distinctUntilChanged()
                            .collect { emit(it) }

                        AppLogger.w("DataStore flow for appSettingsState completed normally (unexpected)")
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutiveErrors++
                        val delayMs =
                            (INITIAL_RETRY_DELAY_MS * (1 shl (consecutiveErrors - 1)))
                                .coerceAtMost(MAX_RETRY_DELAY_MS)

                        val isTerminalLike = consecutiveErrors >= LOG_THRESHOLD
                        val logMessage =
                            "DataStore error in appSettingsState (attempt $consecutiveErrors), " +
                                "restarting in ${delayMs}ms"

                        if (isTerminalLike) {
                            if (consecutiveErrors % LOG_THRESHOLD == 0) {
                                AppLogger.e(logMessage, e)
                            }
                            transitionStatus(
                                TrackingStatus.Error(
                                    TrackingStatus.ErrorType.DATABASE_ERROR,
                                    "Persistent DataStore error: settings observation degraded",
                                ),
                            )
                        } else {
                            AppLogger.w(logMessage)
                        }
                        delay(delayMs.milliseconds)
                    }
                }
            }.stateIn(
                scope,
                SharingStarted.Eagerly,
                AppSettings(),
            )

        /**
         * Returns a flow of application settings.
         *
         * Safe to call from any coroutine context.
         * @throws IOException if DataStore reading fails.
         */
        override fun getAppSettings(): Flow<AppSettings> = appSettingsState

        /**
         * Returns the current application settings once.
         *
         * Ensures a fresh read from DataStore by waiting for the first emission
         * if the internal flow is not currently active.
         */
        override suspend fun getAppSettingsOnce(): AppSettings =
            try {
                dataStore.data.first().toAppSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(
                    "getAppSettingsOnce: CRITICAL failure to read from DataStore, " +
                        "returning defaults",
                    e,
                )
                AppSettings()
            }

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

        private fun Preferences.toAppSettings(): AppSettings {
            val default = AppSettings()
            return default.copy(
                theme = AppTheme.fromValue(this[DataStoreKeys.APP_THEME] ?: -1),
                language = this[DataStoreKeys.APP_LANGUAGE],
                migrationV1Completed = this[DataStoreKeys.MIGRATION_V1_COMPLETED] ?: default.migrationV1Completed,
                onboardingCompleted = this[DataStoreKeys.ONBOARDING_COMPLETED] ?: default.onboardingCompleted,
                notificationPermissionRequested =
                    this[DataStoreKeys.NOTIFICATION_PERMISSION_REQUESTED] ?: default.notificationPermissionRequested,
                wasPhonePermissionRequestedBefore =
                    this[DataStoreKeys.WAS_PHONE_PERMISSION_REQUESTED_BEFORE]
                        ?: default.wasPhonePermissionRequestedBefore,
                phonePermissionRequested =
                    this[DataStoreKeys.PHONE_PERMISSION_REQUESTED] ?: default.phonePermissionRequested,
                benchmarkSimCount = this[DataStoreKeys.BENCHMARK_SIM_COUNT] ?: default.benchmarkSimCount,
                notifyLowMinutes = this[DataStoreKeys.NOTIFY_LOW_MINUTES] ?: default.notifyLowMinutes,
                notifyLowTraffic = this[DataStoreKeys.NOTIFY_LOW_TRAFFIC] ?: default.notifyLowTraffic,
                inaccurateCallAttributionsCount =
                    this[DataStoreKeys.INACCURATE_CALL_ATTRIBUTIONS_COUNT] ?: default.inaccurateCallAttributionsCount,
                callTrackingEnabled = this[DataStoreKeys.CALL_TRACKING_ENABLED] ?: default.callTrackingEnabled,
                widgetLayoutMode = this[DataStoreKeys.WIDGET_LAYOUT_MODE] ?: default.widgetLayoutMode,
                widgetSimArrangement = this[DataStoreKeys.WIDGET_SIM_ARRANGEMENT] ?: default.widgetSimArrangement,
                widgetTransparency = this[DataStoreKeys.WIDGET_TRANSPARENCY] ?: default.widgetTransparency,
                widgetFontSize = this[DataStoreKeys.WIDGET_FONT_SIZE] ?: default.widgetFontSize,
                widgetBackgroundColor = this[DataStoreKeys.WIDGET_BACKGROUND_COLOR] ?: default.widgetBackgroundColor,
                widgetLimitExceeded = this[DataStoreKeys.WIDGET_LIMIT_EXCEEDED] ?: default.widgetLimitExceeded,
            )
        }

        companion object {
            private const val LOG_THRESHOLD = 5
            private const val INITIAL_RETRY_DELAY_MS = 1000L
            private const val MAX_RETRY_DELAY_MS = 30000L
        }
    }
