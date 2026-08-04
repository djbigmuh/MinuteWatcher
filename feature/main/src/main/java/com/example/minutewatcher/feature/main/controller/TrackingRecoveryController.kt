package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.domain.TrackingStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class TrackingRecoveryController
    @Inject
    constructor(
        private val trackingStatusRepository: TrackingStatusRepository,
    ) {
        fun retryAppSearch() {
            trackingStatusRepository.recoverTracking("appsearch")
        }

        fun retryCalls() {
            trackingStatusRepository.recoverTracking("calls")
        }

        fun retryShortcuts() {
            trackingStatusRepository.recoverTracking("shortcuts")
        }

        fun retryTraffic(
            scope: CoroutineScope,
            onRefresh: suspend () -> Unit,
        ) {
            trackingStatusRepository.recoverTracking("traffic")
            scope.launch { onRefresh() }
        }
    }
