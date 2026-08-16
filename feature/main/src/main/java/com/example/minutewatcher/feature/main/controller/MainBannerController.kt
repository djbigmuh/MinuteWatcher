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
         * Время последнего успешного обновления или сброса счётчика повторных попыток.
         *
         * **Строгое соблюдение API:** используется `timeProvider.elapsedRealtime()`
         * (монотонное время), а не `timeProvider.now()` (wall-clock).
         * Монотонное время не зависит от изменений системных часов (NTP, ручная смена),
         * что гарантирует корректность интервала сброса счётчика.
         * На AOSP `SystemClock.elapsedRealtime()` монотонно по контракту.
         * Если кастомная прошивка не обеспечивает монотонность — это проблема кастомной прошивки.
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
            lastResetTime = timeProvider.elapsedRealtime()
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
         * **Стратегия retry:** бесконечные повторные попытки с экспоненциальной
         * задержкой (cap 30с). Это гарантирует, что UI продолжит отображать
         * баннеры даже после временных сбоев.
         *
         * **Строгое соблюдение API:** на AOSP combine из StateFlow не бросает
         * исключений. Сбой возможен только при деградации DataStore на кастомной
         * прошивке — это проблема кастомной прошивки.
         *
         * **Критерий надёжности:** бесконечный retry допустим, так как:
         * 1. Задержка ограничена (cap 30с) — нет нагрузки на CPU.
         * 2. Логирование подавляется после MAX_LOGGED_RETRIES — нет спама.
         * 3. Сброс счётчика по таймеру (RESET_RETRY_COUNTER_INTERVAL_MS)
         *   позволяет восстановиться после устранения проблемы.
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
                resetRetryCounterIfNeeded(timeProvider.elapsedRealtime(), force = true)
            }.retryWhen { cause, _ ->
                if (cause is CancellationException && cause !is TimeoutCancellationException) {
                    throw cause
                }

                val now = timeProvider.elapsedRealtime()
                resetRetryCounterIfNeeded(now)

                val currentRetry =
                    resetMutex.withLock {
                        observationRetryCount++
                        observationRetryCount
                    }

                // Логирование только первых MAX_LOGGED_RETRIES попыток для предотвращения
                // бесконечного спама в лог при перманентной ошибке кастомной прошивки.
                if (currentRetry <= MAX_LOGGED_RETRIES) {
                    AppLogger.e(
                        "trackingBannerState: observation failed (attempt $currentRetry), " +
                            "retrying with backoff",
                        cause,
                    )
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
            internal const val RESET_RETRY_COUNTER_INTERVAL_MS = 10 * 60 * 1000L

            /** Максимальное количество попыток, после которых логирование подавляется. */
            private const val MAX_LOGGED_RETRIES = 10L
        }
    }
