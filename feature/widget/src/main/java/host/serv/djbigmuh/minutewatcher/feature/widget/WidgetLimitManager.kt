package host.serv.djbigmuh.minutewatcher.feature.widget

import android.content.Context
import host.serv.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.firstWithTimeout
import host.serv.djbigmuh.minutewatcher.core.common.util.observeWithRetry
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the widget size limit exceeded flag and probe logic.
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
        private val exceededUpdateCounter = AtomicLong(0L)
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
         * Checks if a probe update should be performed.
         *
         * **Note:** This method does NOT increment the update counter.
         * Callers must ensure the counter is incremented appropriately (e.g., once per update cycle).
         *
         * @return True if the current counter value indicates a probe should occur.
         */
        fun shouldProbe(): Boolean {
            val count = exceededUpdateCounter.get()
            return count > 0 && (count % PROBE_INTERVAL_UPDATES) == 0L
        }

        /**
         * Increments the update counter for exceeded states.
         * Should be called exactly once per update cycle when the limit is exceeded.
         */
        fun incrementUpdateCounter() {
            exceededUpdateCounter.incrementAndGet()
        }

        fun resetCounter() {
            exceededUpdateCounter.set(0L)
        }

        /**
         * Updates the in-memory cache of the limit exceeded flag without writing to DataStore.
         */
        fun updateCache(value: Boolean) {
            cachedLimitExceeded = value
        }

        fun isLimitExceeded(): Boolean? = cachedLimitExceeded

    @Suppress("ThrowsCount") // Multiple exception types
        suspend fun setWidgetLimitExceeded(value: Boolean) {
            if (cachedLimitExceeded == null) {
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
                    try {
                        appSettingsRepository.updateAppSettings {
                            it.copy(widgetLimitExceeded = value)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Failed to set widget limit exceeded flag to $value", e)
                    }
                    return
                }
            }

            if (cachedLimitExceeded == value) {
                return
            }

            cachedLimitExceeded = value
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

        /**
         * Performs a probe update to check if the widget size limit is still exceeded.
         *
         * **Contract:** Probe updates must not execute concurrently with other widget updates;
         * serialization is ensured by update coalescing in WidgetUpdaterImpl (single active update via
         * coalescingMutex/activeUpdate), within which performProbeUpdate is invoked. Flag is only changed
         * based on Success or LimitExceeded results.
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
