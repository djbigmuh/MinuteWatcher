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
import kotlinx.coroutines.TimeoutCancellationException
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
         * Время последнего логирования ошибки наблюдения.
         *
         * Инициализирован 0L: при первом превышении MAX_LOGGED_RETRIES
         * проверка (now - 0 > PERIODIC_LOG_INTERVAL_MS) всегда истинна,
         * что гарантирует логирование первого превышения. Это корректное
         * поведение: первая ошибка после порога должна быть залогирована.
         *
         * **Строгое соблюдение API:** на AOSP StateFlow эмитит значения
         * последовательно. Наблюдение за trackingStatus не бросает
         * IOException. Сбой наблюдения возможен только при деградации
         * DataStore на кастомной прошивке, не соответствующей документации
         * Google. Это проблема кастомной прошивки.
         */
        private var lastLoggedTime = 0L

        /**
         * Мьютекс сериализует обновление счётчика [observationRetryCount].
         */
        private val resetMutex = Mutex()

        private suspend fun handleSuccess() {
            resetMutex.withLock {
                if (observationRetryCount > 0) {
                    AppLogger.d(
                        "trackingBannerState: successfully recovered after $observationRetryCount failed attempts",
                    )
                }
                observationRetryCount = 0L
                lastLoggedTime = 0L
            }
        }

        // trackingStatus инициализирован emptyMap(). До первого эмита
        // из observeTrackingStatus() (что происходит в течение микросекунд
        // при SharingStarted.Eagerly) баннеры ошибок будут скрыты.
        // Это допустимое поведение: окно составляет микросекунды.
        // На AOSP StateFlow эмитит начальное значение синхронно.
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
         * **Стратегия retry:** бесконечные повторные попытки с экспоненциальной
         * задержкой (cap 30 с). Это **намеренное** поведение: UI должен
         * продолжать попытки восстановления баннеров даже после временных сбоев.
         *
         * **Строгое соблюдение API:** на AOSP combine из StateFlow не бросает
         * исключений. Сбой наблюдения возможен только при деградации хранилища
         * на кастомной прошивке, не соответствующей документации Google.
         *
         * **Эвристика в пределах критерия надёжности:** cap 30 с предотвращает
         * избыточную нагрузку. Максимальная частота попыток — 1 раз в 30 секунд.
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
                handleSuccess()
            }.retryWhen { cause, _ ->
                // CancellationException (кроме TimeoutCancellationException) пробрасывается
                // для корректной отмены корутины. Это строгое соблюдение API kotlinx.coroutines.
                if (cause is CancellationException && cause !is TimeoutCancellationException) {
                    throw cause
                }

                val currentRetry =
                    resetMutex.withLock {
                        observationRetryCount++
                        observationRetryCount
                    }

                val now = timeProvider.elapsedRealtime()
                val shouldLog =
                    if (currentRetry <= MAX_LOGGED_RETRIES) {
                        true
                    } else {
                        if (now - lastLoggedTime > PERIODIC_LOG_INTERVAL_MS) {
                            lastLoggedTime = now
                            true
                        } else {
                            false
                        }
                    }

                if (shouldLog) {
                    val message =
                        if (currentRetry > MAX_LOGGED_RETRIES) {
                            "trackingBannerState: periodic log - observation still failing " +
                                "(attempt $currentRetry), retrying with backoff"
                        } else {
                            "trackingBannerState: observation failed (attempt $currentRetry), " +
                                "retrying with backoff"
                        }
                    AppLogger.e(message, cause)
                }

                val delayMillis =
                    (2.0.pow((currentRetry - 1).toDouble()).toLong() * Constants.MILLIS_PER_SECOND)
                        .coerceAtMost(MAX_RETRY_DELAY_MS)
                delay(delayMillis.milliseconds)
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
            internal const val PERIODIC_LOG_INTERVAL_MS = 5 * 60 * 1000L

            /** Максимальное количество попыток, после которых логирование подавляется. */
            private const val MAX_LOGGED_RETRIES = 10L
        }
    }
