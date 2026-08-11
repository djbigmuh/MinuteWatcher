package com.example.minutewatcher.feature.main.controller

import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.CancellationException
import javax.inject.Inject
import kotlin.math.pow

class MainBannerController
    @Inject
    constructor(
        trackingStatusRepository: TrackingStatusRepository,
        permissionRepository: PermissionRepository,
        appSettingsRepository: AppSettingsRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val _isPhonePermissionPermanentlyDenied = MutableStateFlow(false)
        val isPhonePermissionPermanentlyDenied: StateFlow<Boolean> = _isPhonePermissionPermanentlyDenied.asStateFlow()

        fun setPhonePermissionPermanentlyDenied(denied: Boolean) {
            _isPhonePermissionPermanentlyDenied.value = denied
        }

        private val _isNotificationPermissionPermanentlyDenied = MutableStateFlow(false)
        val isNotificationPermissionPermanentlyDenied: StateFlow<Boolean> =
            _isNotificationPermissionPermanentlyDenied.asStateFlow()

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

        private val bannerExtraData: Flow<Triple<Boolean, Boolean, AppSettings>> =
            combine(
                _isPhonePermissionPermanentlyDenied,
                _isNotificationPermissionPermanentlyDenied,
                appSettingsRepository.observeAppSettings(),
            ) { phoneDenied, notifDenied, settings ->
                Triple(phoneDenied, notifDenied, settings)
            }

        /**
         * Цепочка расчета состояния баннеров с бесконечной стратегией повторных попыток.
         *
         * Стратегия Retry:
         * 1. Экспоненциальная задержка (exponential backoff), начиная с 1с, с ограничением [MAX_RETRY_DELAY_MS].
         * 2. Если количество попыток достигает [MAX_ATTEMPTS_BEFORE_FALLBACK], в UI эмитится состояние
         *    по умолчанию [TrackingBannerState]. Это предотвращает «зависание» интерфейса в состоянии
         *    загрузки или ошибки при длительных сбоях в репозиториях.
         * 3. Попытки продолжаются бесконечно, чтобы UI мог автоматически восстановиться после
         *    устранения причины сбоя.
         */
        val trackingBannerState: StateFlow<TrackingBannerState> =
            combine(
                trackingStatus,
                permissionRepository.phoneStatePermissionGranted,
                permissionRepository.usageStatsPermissionGranted,
                permissionRepository.notificationPermissionGranted,
                bannerExtraData,
            ) { status, phone, usage, notif, extra ->
                TrackingBannerMapper.mapToBannerState(
                    statusMap = status,
                    phoneGranted = phone,
                    usageGranted = usage,
                    notificationsGranted = notif,
                    phonePermanentlyDenied = extra.first,
                    notificationPermanentlyDenied = extra.second,
                    settings = extra.third,
                )
            }.retryWhen { cause, attempt ->
                if (cause is CancellationException) throw cause

                AppLogger.e("RETRY ($attempt): Failed to compute banner state", cause)

                // Эмитим дефолтное состояние после нескольких неудачных попыток,
                // чтобы UI не застыл в неопределенности, но продолжаем попытки.
                if (attempt >= MAX_ATTEMPTS_BEFORE_FALLBACK) {
                    emit(TrackingBannerState())
                }

                val delayMillis =
                    (2.0.pow(attempt.toDouble() - 1.0).toLong() * Constants.MILLIS_PER_SECOND)
                        .coerceAtMost(MAX_RETRY_DELAY_MS)
                delay(delayMillis)
                true // Retrying infinitely with capped delay
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = TrackingBannerState(),
            )

        companion object {
            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val MAX_ATTEMPTS_BEFORE_FALLBACK = 5L

            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val MAX_RETRY_DELAY_MS = 30_000L
        }
    }
