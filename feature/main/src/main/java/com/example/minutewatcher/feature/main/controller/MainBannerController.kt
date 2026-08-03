package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

class MainBannerController
    @Inject
    constructor(
        trackingStatusRepository: TrackingStatusRepository,
        permissionRepository: PermissionRepository,
        appSettingsRepository: AppSettingsRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val _isPhonePermissionPermanentlyDenied = MutableStateFlow(false)

        fun setPhonePermissionPermanentlyDenied(denied: Boolean) {
            _isPhonePermissionPermanentlyDenied.value = denied
        }

        private val _isNotificationPermissionPermanentlyDenied = MutableStateFlow(false)

        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) {
            _isNotificationPermissionPermanentlyDenied.value = denied
        }

        val trackingStatus: StateFlow<Map<String, TrackingStatus>> =
            trackingStatusRepository
                .observeTrackingStatus()
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = emptyMap(),
                )

        val trackingBannerState: StateFlow<TrackingBannerState> =
            combine(
                trackingStatus,
                permissionRepository.phoneStatePermissionGranted,
                permissionRepository.usageStatsPermissionGranted,
                permissionRepository.notificationPermissionGranted,
                combine(
                    _isPhonePermissionPermanentlyDenied,
                    _isNotificationPermissionPermanentlyDenied,
                    appSettingsRepository.observeAppSettings(),
                ) { phoneDenied, notifDenied, settings ->
                    Triple(phoneDenied, notifDenied, settings)
                },
            ) { status, phone, usage, notif, triple ->
                val (phoneDenied, notifDenied, settings) = triple
                TrackingBannerMapper.mapToBannerState(
                    statusMap = status,
                    phoneGranted = phone,
                    usageGranted = usage,
                    notificationsGranted = notif,
                    phonePermanentlyDenied = phoneDenied,
                    notificationPermanentlyDenied = notifDenied,
                    settings = settings,
                )
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = TrackingBannerState(),
            )
    }
