package com.example.minutewatcher.feature.main.controller

import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

class MainBannerController
    @Inject
    constructor(
        trackingStatusRepository: TrackingStatusRepository,
        permissionRepository: PermissionRepository,
        appSettingsRepository: AppSettingsRepository,
        @ApplicationScope private val scope: CoroutineScope,
        private val timeProvider: TimeProvider,
    ) {
        private val isPhonePermissionPermanentlyDenied = MutableStateFlow(false)

        fun setPhonePermissionPermanentlyDenied(denied: Boolean) {
            isPhonePermissionPermanentlyDenied.value = denied
        }

        private val isNotificationPermissionPermanentlyDenied = MutableStateFlow(false)

        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) {
            isNotificationPermissionPermanentlyDenied.value = denied
        }

        private val observationRetryCount = AtomicLong(0L)
        private var lastResetTime = 0L

        init {
            lastResetTime = timeProvider.now()
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
                isPhonePermissionPermanentlyDenied,
                isNotificationPermissionPermanentlyDenied,
                appSettingsRepository.observeAppSettings(),
            ) { phoneDenied, notifDenied, settings ->
                Triple(phoneDenied, notifDenied, settings)
            }

        /**
         * Состояние баннеров отслеживания.
         *
         * Использует WhileSubscribed(5000): upstream flow активен пока есть
         * подписчики и в течение 5 секунд после ухода последнего подписчика.
         * Это штатное поведение: при пересоздании UI (поворот экрана, переход
         * между экранами) flow пересоздаётся и баннеры обновляются.
         *
         * Строгое соблюдение API: на AOSP combine из StateFlow не бросает
         * исключений. Сбой возможен только при деградации DataStore
         * на кастомных прошивках, не соответствующих документации Google.
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
            }.onEach {
                observationRetryCount.set(0)
                lastResetTime = timeProvider.now()
            }.retryWhen { cause, _ ->
                // CancellationException пробрасывается для корректной остановки корутин.
                if (cause is CancellationException) throw cause

                val now = timeProvider.now()
                if ((now - lastResetTime) > RESET_RETRY_COUNTER_INTERVAL_MS) {
                    observationRetryCount.set(0)
                    lastResetTime = now
                }

                val currentRetry = observationRetryCount.incrementAndGet()
                AppLogger.e("Failed to compute banner state (attempt $currentRetry)", cause)

                val delayMillis =
                    (2.0.pow(currentRetry.toDouble()).toLong() * Constants.MILLIS_PER_SECOND)
                        .coerceAtMost(MAX_RETRY_DELAY_MS)
                delay(delayMillis.milliseconds)
                true // Retrying infinitely with capped delay; UI retains last successful state
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                initialValue = TrackingBannerState(),
            )

        companion object {
            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val MAX_RETRY_DELAY_MS = 30_000L

            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val RESET_RETRY_COUNTER_INTERVAL_MS = 10 * 60 * 1000L
        }
    }
