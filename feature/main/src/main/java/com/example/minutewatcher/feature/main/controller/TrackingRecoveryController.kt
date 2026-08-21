package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.domain.TrackingStatusRepository
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
            trackingStatusRepository.recoverTracking("appsearch")
        }

        /**
         * Retries call tracking recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        suspend fun retryCalls() {
            trackingStatusRepository.recoverTracking("calls")
        }

        /**
         * Retries shortcut creation recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        suspend fun retryShortcuts() {
            trackingStatusRepository.recoverTracking("shortcuts")
        }

        /**
         * Retries traffic tracking recovery.
         *
         * @param onRefresh Callback to refresh data after recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        suspend fun retryTraffic(onRefresh: suspend () -> Unit) {
            trackingStatusRepository.recoverTracking("traffic")
            onRefresh()
        }
    }
