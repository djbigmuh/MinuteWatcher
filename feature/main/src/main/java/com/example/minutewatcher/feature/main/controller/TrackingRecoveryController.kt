package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.domain.TrackingStatusRepository
import javax.inject.Inject

class TrackingRecoveryController
    @Inject
    constructor(
        private val trackingStatusRepository: TrackingStatusRepository,
    ) {
        suspend fun retryAppSearch() {
            trackingStatusRepository.recoverTracking("appsearch")
        }

        suspend fun retryCalls() {
            trackingStatusRepository.recoverTracking("calls")
        }

        suspend fun retryShortcuts() {
            trackingStatusRepository.recoverTracking("shortcuts")
        }

        suspend fun retryTraffic(onRefresh: suspend () -> Unit) {
            trackingStatusRepository.recoverTracking("traffic")
            onRefresh()
        }
    }
