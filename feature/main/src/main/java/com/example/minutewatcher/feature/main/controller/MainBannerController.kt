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
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        private var observationRetryCount = 0L

        /**
         * Время последнего успешного обновления или сброса счетчика попыток.
         */
        private var lastResetTime = 0L

        /**
         * Мьютекс сериализует обновление пары (retryCount, lastResetTime).
         *
         * **Строгое соблюдение API:** на AOSP StateFlow эмитит значения
         * последовательно. Мьютекс защищает только инфраструктуру подписки,
         * не данные. Если кастомная прошивка эмитит значения вне порядка —
         * это проблема кастомной прошивки.
         */
        private val resetMutex = Mutex()

        // Мьютекс защищает check-then-act над парой (observationRetryCount, lastResetTime).
        // Без него при смене диспетчера на многопоточный возможен race:
        // два потока одновременно прочитают lastResetTime, оба сбросят счётчик
        // и оба запишут now, что приведёт к двойному сбросу.
        private suspend fun resetRetryCounterIfNeeded(
            now: Long,
            force: Boolean = false,
        ) {
            resetMutex.withLock {
                if (force || (now - lastResetTime) > RESET_RETRY_COUNTER_INTERVAL_MS) {
                    observationRetryCount = 0L
                    lastResetTime = now
                }
            }
        }

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
         * **Контракт AOSP:** на AOSP combine из StateFlow не бросает исключений.
         *
         * **Отклонение кастомной прошивки:** Сбой возможен только при деградации DataStore
         * на кастомных прошивках. Это проблема кастомной прошивки.
         *
         * **Стратегия приложения:** Используется WhileSubscribed(5000); при сбое выполняется
         * бесконечный ретрай с capped delay.
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
                resetRetryCounterIfNeeded(timeProvider.now(), force = true)
            }.retryWhen { cause, _ ->
                // Стратегия повторных попыток:
                // 1. CancellationException пробрасывается для корректной кооперативной отмены.
                // 2. Задержка: экспоненциальная, 2^(attempt-1) секунд, потолок MAX_RETRY_DELAY_MS.
                // 3. Сброс счётчика: каждые RESET_RETRY_COUNTER_INTERVAL_MS (10 мин)
                //    после последнего успешного обновления или последнего сброса.
                //
                // **Контракт AOSP:** combine из StateFlow не бросает исключений.
                // Сбой возможен только при деградации DataStore на кастомных прошивках.
                //
                // **Отклонение кастомной прошивки:** Если прошивка не обеспечивает
                // корректную работу StateFlow — это проблема кастомной прошивки.
                // Бесконечный retry с потолком задержки — защитный механизм.
                if (cause is CancellationException) throw cause

                val now = timeProvider.now()
                resetRetryCounterIfNeeded(now)

                val currentRetry =
                    resetMutex.withLock {
                        observationRetryCount++
                        observationRetryCount
                    }
                AppLogger.e("Failed to compute banner state (attempt $currentRetry)", cause)

                val delayMillis =
                    (2.0.pow((currentRetry - 1).toDouble()).toLong() * Constants.MILLIS_PER_SECOND)
                        .coerceAtMost(MAX_RETRY_DELAY_MS)
                delay(delayMillis.milliseconds)

                // Бесконечный retry с capped delay (MAX_RETRY_DELAY_MS).
                // Остановка подписки приведёт к потере баннеров статуса для пользователя.
                //
                // Строгое соблюдение API: на AOSP combine из StateFlow не бросает
                // исключений. Сбой возможен только при деградации DataStore
                // на кастомных прошивках.
                //
                // Отклонение кастомной прошивки: если прошивка не обеспечивает
                // корректную работу StateFlow — это проблема кастомной прошивки.
                // Бесконечный retry с capped delay — защитный механизм,
                // гарантирующий восстановление без вмешательства пользователя.
                // UI сохраняет последнее успешное состояние во время повторов —
                // нет мерцания.
                true
            }.stateIn(
                scope = scope,
                started =
                    SharingStarted.WhileSubscribed(
                        stopTimeoutMillis = Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS,
                    ),
                initialValue = TrackingBannerState(),
            )

        companion object {
            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val MAX_RETRY_DELAY_MS = 30_000L

            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val RESET_RETRY_COUNTER_INTERVAL_MS = 10 * 60 * 1000L
        }
    }
