package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

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
    ) {
        private var exceededUpdateCounter = 0L

        fun shouldProbe(): Boolean {
            val count = ++exceededUpdateCounter
            return count % PROBE_INTERVAL_UPDATES == 0L
        }

        fun resetCounter() {
            exceededUpdateCounter = 0L
        }

        suspend fun setWidgetLimitExceeded(value: Boolean) {
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

        suspend fun performProbeUpdate(
            context: Context,
            updateBlock: suspend (Context, Boolean) -> UpdateResult,
        ): UpdateResult {
            setWidgetLimitExceeded(value = false)
            try {
                val probeResult = updateBlock(context, true)
                if (probeResult is UpdateResult.GenericFailure || probeResult is UpdateResult.LimitExceeded) {
                    setWidgetLimitExceeded(value = true)
                }
                return probeResult
            } catch (e: CancellationException) {
                setWidgetLimitExceeded(value = true)
                throw e
            } catch (e: Exception) {
                AppLogger.e("Widget: performProbeUpdate failed", e)
                setWidgetLimitExceeded(value = true)
                throw e
            }
        }

        companion object {
            const val PROBE_INTERVAL_UPDATES = 10
        }
    }
