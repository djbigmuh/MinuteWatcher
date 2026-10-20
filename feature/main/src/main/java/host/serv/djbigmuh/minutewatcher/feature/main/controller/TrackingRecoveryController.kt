package host.serv.djbigmuh.minutewatcher.feature.main.controller

import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusRepository
import host.serv.djbigmuh.minutewatcher.core.model.TrackingKey
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * TrackingRecoveryController provides methods to recover tracking status for various components.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [CancellationException].
 *
 * @property trackingStatusRepository Repository for managing tracking status and recovery.
 */
class TrackingRecoveryController
    @Inject
    constructor(
        private val trackingStatusRepository: TrackingStatusRepository,
    ) {
        /**
         * Retries app search tracking recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        suspend fun retryAppSearch() {
            trackingStatusRepository.recoverTracking(TrackingKey.APPSEARCH)
        }

        /**
         * Retries call tracking recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        suspend fun retryCalls() {
            trackingStatusRepository.recoverTracking(TrackingKey.CALLS)
        }

        /**
         * Retries shortcut creation recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        suspend fun retryShortcuts() {
            trackingStatusRepository.recoverTracking(TrackingKey.SHORTCUTS)
        }

        /**
         * Retries traffic tracking recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        suspend fun retryTraffic() {
            trackingStatusRepository.recoverTracking(TrackingKey.TRAFFIC)
        }

        /**
         * Retries SIM discovery recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        suspend fun retrySimDiscovery() {
            trackingStatusRepository.recoverTracking(TrackingKey.SIM_DISCOVERY)
        }
    }
