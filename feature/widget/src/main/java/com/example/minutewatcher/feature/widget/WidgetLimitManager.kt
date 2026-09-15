package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the widget size limit exceeded flag and probe logic.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
open class WidgetLimitManager
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val exceededUpdateCounter = AtomicLong(0L)

        @Volatile
        private var cachedLimitExceeded: Boolean? = null

        init {
            scope.launch {
                appSettingsRepository.observeAppSettings().collectLatest { settings ->
                    cachedLimitExceeded = settings.widgetLimitExceeded
                }
            }
        }

        /**
         * Checks if a probe update should be performed.
         *
         * **Note:** This method does NOT increment the update counter.
         * Callers must ensure the counter is incremented appropriately (e.g., once per update cycle).
         *
         * @return True if the current counter value indicates a probe should occur.
         */
        open fun shouldProbe(): Boolean {
            val count = exceededUpdateCounter.get()
            return count > 0 && (count % PROBE_INTERVAL_UPDATES) == 0L
        }

        /**
         * Increments the update counter for exceeded states.
         * Should be called exactly once per update cycle when the limit is exceeded.
         */
        open fun incrementUpdateCounter() {
            exceededUpdateCounter.incrementAndGet()
        }

        open fun resetCounter() {
            exceededUpdateCounter.set(0L)
        }

        /**
         * Updates the in-memory cache of the limit exceeded flag without writing to DataStore.
         */
        open fun updateCache(value: Boolean) {
            cachedLimitExceeded = value
        }

        open fun isLimitExceeded(): Boolean? = cachedLimitExceeded

        open suspend fun setWidgetLimitExceeded(value: Boolean) {
            cachedLimitExceeded = value
            try {
                val current = appSettingsRepository.observeAppSettings().value
                if (current.widgetLimitExceeded == value) return
                appSettingsRepository.updateAppSettings {
                    it.copy(widgetLimitExceeded = value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to set widget limit exceeded flag to $value", e)
            }
        }

        /**
         * Performs a probe update to check if the widget size limit is still exceeded.
         *
         * **Contract:** This method must be called under the updateMutex in WidgetUpdaterImpl
         * to prevent concurrent persistent state changes. Flag is only changed based on
         * Success or LimitExceeded results.
         *
         * @param context The application context.
         * @param updateBlock The suspend block that performs the actual update.
         * @return The result of the update attempt.
         */
        suspend fun performProbeUpdate(
            context: Context,
            updateBlock: suspend (Context, Boolean) -> UpdateResult,
        ): UpdateResult {
            try {
                val isFullAttempt = true
                val probeResult = updateBlock(context, isFullAttempt)
                when (probeResult) {
                    is UpdateResult.Success -> {
                        setWidgetLimitExceeded(value = false)
                    }

                    is UpdateResult.LimitExceeded -> {
                        setWidgetLimitExceeded(value = true)
                    }

                    is UpdateResult.GenericFailure -> {
                        // Transient probe failure: do NOT change persistent flag.
                        // Simplified mode will persist until next successful probe.
                        AppLogger.w("WidgetLimitManager: probe failed transiently, limit flag unchanged")
                    }
                }
                return probeResult
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Widget: performProbeUpdate failed", e)
                throw e
            }
        }

        companion object {
            const val PROBE_INTERVAL_UPDATES = 10
        }
    }
