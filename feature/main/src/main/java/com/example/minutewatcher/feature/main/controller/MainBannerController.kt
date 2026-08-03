package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import com.example.minutewatcher.core.common.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

class MainBannerController @Inject constructor(
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
                started = SharingStarted.WhileSubscribed(Constants.Flow.STATE_FLOW_STOP_TIMEOUT_MS),
                initialValue = emptyMap(),
            )

    val trackingBannerState: StateFlow<TrackingBannerState> =
        combine(
            combine(
                trackingStatus,
                permissionRepository.phoneStatePermissionGranted,
                permissionRepository.usageStatsPermissionGranted,
            ) { statusMap, phoneGranted, usageGranted ->
                Triple(statusMap, phoneGranted, usageGranted)
            },
            combine(
                permissionRepository.notificationPermissionGranted,
                _isPhonePermissionPermanentlyDenied,
                _isNotificationPermissionPermanentlyDenied,
                appSettingsRepository.observeAppSettings(),
            ) { notificationsGranted, phonePermanentlyDenied, notificationPermanentlyDenied, settings ->
                BannerInputsPart2(notificationsGranted, phonePermanentlyDenied, notificationPermanentlyDenied, settings)
            },
        ) { part1, part2 ->
            val (statusMap, phoneGranted, usageGranted) = part1
            val (notificationsGranted, phonePermanentlyDenied, notificationPermanentlyDenied, settings) = part2

            TrackingBannerMapper.mapToBannerState(
                statusMap = statusMap,
                phoneGranted = phoneGranted,
                usageGranted = usageGranted,
                notificationsGranted = notificationsGranted,
                phonePermanentlyDenied = phonePermanentlyDenied,
                notificationPermanentlyDenied = notificationPermanentlyDenied,
                settings = settings,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(Constants.Flow.STATE_FLOW_STOP_TIMEOUT_MS),
            initialValue = TrackingBannerState(),
        )


    private data class BannerInputsPart2(
        val notificationsGranted: Boolean,
        val phonePermanentlyDenied: Boolean,
        val notificationPermanentlyDenied: Boolean,
        val settings: AppSettings,
    )
}
