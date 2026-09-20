package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimUsageRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CallTrackingSettingsHandler
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val simUsageRepository: SimUsageRepository,
        private val dataChangeNotifier: DataChangeNotifier,
        private val retryHelper: DataStoreRetryHelper,
    ) {
        suspend fun isCallTrackingEnabled(): Boolean {
            val settings =
                retryHelper
                    .readWithRetry(
                        label = "isCallTrackingEnabled",
                    ) {
                        appSettingsRepository.getAppSettings()
                    }.getOrThrow()
            return settings.callTrackingEnabled
        }

        suspend fun writeCallDuration(
            identity: String,
            durationMinutes: Int,
            isFallbackUsed: Boolean,
            notify: Boolean,
            callMarker: Long,
        ): Boolean {
            val incremented = simUsageRepository.incrementMinutesUsedForCall(identity, durationMinutes, callMarker)
            if (!incremented) return true

            if (isFallbackUsed) {
                appSettingsRepository.updateAppSettings {
                    it.copy(inaccurateCallAttributionsCount = it.inaccurateCallAttributionsCount + 1)
                }
            }
            if (notify) {
                dataChangeNotifier.notifyDataChanged()
            }
            AppLogger.d(
                "Call recorded: $durationMinutes min for $identity " +
                    "(marker=$callMarker, fallback=$isFallbackUsed)",
            )
            return true
        }
    }
