package com.github.djbigmuh.minutewatcher.feature.widget

import com.github.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.firstWithTimeout
import com.github.djbigmuh.minutewatcher.core.common.util.observeWithRetry
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the widget size limit exceeded flag state.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class WidgetLimitManager
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val started = AtomicBoolean(false)

        @Volatile
        private var cachedLimitExceeded: Boolean? = null

        /**
         * Starts observation explicitly from Application.onCreate.
         * The constructor has no side effects, and repeated calls are ignored.
         * Idempotent and thread-safe.
         */
        fun start() {
            if (started.compareAndSet(false, true)) {
                scope.launch {
                    appSettingsRepository
                        .observeAppSettings()
                        .observeWithRetry(
                            label = "WidgetLimitManager: appSettings",
                            logThreshold = 3,
                            onPersistentFailure = { e ->
                                cachedLimitExceeded = null
                                AppLogger.e("WidgetLimitManager: persistent failure observing app settings", e)
                            },
                            onRecovered = {
                                AppLogger.d("WidgetLimitManager: recovered observing app settings")
                            },
                            onSourceCompleted = {
                                AppLogger.w("WidgetLimitManager: source completed observing app settings")
                            },
                        ).collectLatest { settings ->
                            cachedLimitExceeded = settings.widgetLimitExceeded
                        }
                }
            }
        }

        /**
         * Updates the in-memory cache of the limit exceeded flag without writing to DataStore.
         */
        fun updateCache(value: Boolean) {
            cachedLimitExceeded = value
        }

        fun isLimitExceeded(): Boolean? = cachedLimitExceeded

        suspend fun setWidgetLimitExceeded(value: Boolean) {
            if (cachedLimitExceeded == null) {
                @Suppress("TooGenericExceptionCaught")
                val settings =
                    try {
                        appSettingsRepository
                            .getAppSettings()
                            .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w("WidgetLimitManager: error reading app settings", e)
                        null
                    }
                if (settings != null) {
                    cachedLimitExceeded = settings.widgetLimitExceeded
                } else {
                    AppLogger.w("WidgetLimitManager: timeout reading app settings when cache was null")
                    cachedLimitExceeded = value
                    persistLimitExceededFlag(value)
                    return
                }
            }

            if (cachedLimitExceeded == value) {
                return
            }

            cachedLimitExceeded = value
            persistLimitExceededFlag(value)
        }

        private suspend fun persistLimitExceededFlag(value: Boolean) {
            @Suppress("TooGenericExceptionCaught")
            try {
                appSettingsRepository.updateAppSettings {
                    it.copy(widgetLimitExceeded = value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to set widget limit exceeded flag to $value", e)
            }
        }
    }
