package com.example.minutewatcher.feature.main.state

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class TrackingBannerStateHolder(
    private val usageStateRepository: UsageStateRepository,
    private val permissionRepository: PermissionRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope,
    private val stateFlowTimeout: Long,
) {
    private val _isPhonePermissionPermanentlyDenied = MutableStateFlow(false)
    val isPhonePermissionPermanentlyDenied: StateFlow<Boolean> = _isPhonePermissionPermanentlyDenied.asStateFlow()

    fun setPhonePermissionPermanentlyDenied(denied: Boolean) {
        _isPhonePermissionPermanentlyDenied.value = denied
    }

    private val _isNotificationPermissionPermanentlyDenied = MutableStateFlow(false)
    val isNotificationPermissionPermanentlyDenied: StateFlow<Boolean> = _isNotificationPermissionPermanentlyDenied.asStateFlow()

    fun setNotificationPermissionPermanentlyDenied(denied: Boolean) {
        _isNotificationPermissionPermanentlyDenied.value = denied
    }

    val trackingStatus: StateFlow<Map<String, TrackingStatus>> =
        usageStateRepository
            .observeTrackingStatus()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stateFlowTimeout),
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
                settings = settings
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stateFlowTimeout),
            initialValue = TrackingBannerState(),
        )

    private data class BannerInputsPart2(
        val notificationsGranted: Boolean,
        val phonePermanentlyDenied: Boolean,
        val notificationPermanentlyDenied: Boolean,
        val settings: AppSettings,
    )
}
