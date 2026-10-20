package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreRetryHelper
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
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

        /**
         * Writes call duration for the specified SIM identity.
         *
         * The call signature ensures idempotency; if a call with the given signature was already recorded,
         * no further updates or notifications are performed.
         */
        suspend fun writeCallDuration(
            identity: String,
            durationMinutes: Int,
            isFallbackUsed: Boolean,
            notify: Boolean,
            callSignature: String,
        ) {
            val incremented = simUsageRepository.incrementMinutesUsedForCall(identity, durationMinutes, callSignature)
            if (!incremented) return

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
                    "(signature=$callSignature, fallback=$isFallbackUsed)",
            )
        }
    }
