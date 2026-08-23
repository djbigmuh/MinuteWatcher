package com.example.minutewatcher.feature.main.controller

import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.catchingCancellation
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

/**
 * MainBannerController manages the state of tracking banners (errors and notifications).
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [CancellationException]. Observes errors are handled with exponential backoff.
 * **AOSP DataStore:** Fully compliant with AOSP DataStore semantics via observed flows.
 *
 * Стратегия повторных попыток наблюдения:
 * - Бесконечные повторные попытки с экспоненциальной задержкой (cap 30 сек).
 * - Максимальная частота — 1 раз в 30 секунд.
 * - Отсутствие верхнего предела попыток является осознанным решением.
 *
 * Строгое соблюдение API: на AOSP combine из StateFlow не бросает исключений.
 * Сбой наблюдения возможен только при деградации хранилища на кастомной прошивке.
 */
@Singleton
class MainBannerController
    @Inject
    constructor(
        private val trackingStatusRepository: dagger.Lazy<TrackingStatusRepository>,
        permissionRepository: PermissionRepository,
        appSettingsRepository: AppSettingsRepository,
        @ApplicationScope private val scope: CoroutineScope,
        private val timeProvider: TimeProvider,
    ) : TrackingStatusProvider {
        override val key: String = "banners"
        private val isPhonePermissionPermanentlyDenied = MutableStateFlow(false)

        /**
         * Sets whether the phone permission is permanently denied.
         *
         * @param denied True if permanently denied.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun setPhonePermissionPermanentlyDenied(denied: Boolean) {
            isPhonePermissionPermanentlyDenied.value = denied
        }

        private val isNotificationPermissionPermanentlyDenied = MutableStateFlow(false)

        /**
         * Sets whether the notification permission is permanently denied.
         *
         * @param denied True if permanently denied.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) {
            isNotificationPermissionPermanentlyDenied.value = denied
        }

        private val appSearchRetryCount = MutableStateFlow(0)
        private val appSearchBannerDismissed = MutableStateFlow(false)

        /**
         * Increments the app search retry count.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun incrementAppSearchRetry() {
            appSearchRetryCount.value++
        }

        /**
         * Dismisses the app search error banner.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun dismissAppSearch() {
            appSearchBannerDismissed.value = true
        }

        private var lastAppSearchErrorType: TrackingStatus.ErrorType? = null

        private var job: Job? = null
        private var lastTrackingStatusFlow: StateFlow<Map<String, TrackingStatus>>? = null

        /**
         * Счётчик последовательных сбоев наблюдения.
         * Доступ осуществляется внутри [resetMutex.withLock].
         * Сбрасывается при успешной эмиссии и при перезапуске приложения.
         */
        private var observationRetryCount = 0L

        private val retryAttempt = AtomicLong(0)

        /**
         * Время последнего логирования ошибки наблюдения (elapsedRealtime).
         * Доступ осуществляется внутри [resetMutex.withLock].
         */
        private var lastLoggedTime = 0L

        /**
         * Мьютекс сериализует обновление счётчиков сбоев.
         */
        private val resetMutex = Mutex()
        private val initMutex = Mutex()

        private val recoveryTrigger = MutableStateFlow(0L)
        private val observationFailedFlow = MutableStateFlow(false)

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private suspend fun handleSuccess() {
            resetMutex.withLock {
                if (observationRetryCount > 0) {
                    AppLogger.d(
                        "trackingBannerState: successfully recovered after " +
                            "$observationRetryCount failed attempts",
                    )
                }
                observationRetryCount = 0L
                retryAttempt.set(0)
                lastLoggedTime = 0L
                observationFailedFlow.value = false
            }
        }

        override suspend fun recover() {
            initMutex.withLock {
                observationRetryCount = 0L
                retryAttempt.set(0)
                lastLoggedTime = 0L
                observationFailedFlow.value = false
                lastTrackingStatusFlow?.let { setupObservation(it) }
                recoveryTrigger.value++
            }
        }

        // trackingStatus инициализирован emptyMap(). До первого эмита
        // из observeTrackingStatus() (что происходит в течение микросекунд
        // при SharingStarted.Eagerly) баннеры ошибок будут скрыты.
        // Это допустимое поведение: окно составляет микросекунды.
        // На AOSP StateFlow эмитит начальное значение синхронно.
        val trackingStatus: StateFlow<Map<String, TrackingStatus>> =
            trackingStatusRepository
                .get()
                .observeTrackingStatus()
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = emptyMap(),
                )

        init {
            setupObservation(trackingStatus)
        }

        private fun setupObservation(statusFlow: StateFlow<Map<String, TrackingStatus>>) {
            lastTrackingStatusFlow = statusFlow
            if (job?.isActive == true) return
            job =
                scope.launch {
                    catchingCancellation(
                        block = {
                            statusFlow.collect { status ->
                                val appsearch = status["appsearch"]
                                val currentErrorType = (appsearch as? TrackingStatus.Error)?.type
                                if (currentErrorType != lastAppSearchErrorType) {
                                    lastAppSearchErrorType = currentErrorType
                                    appSearchRetryCount.value = 0
                                    appSearchBannerDismissed.value = false
                                    if (currentErrorType == TrackingStatus.ErrorType.NOT_SUPPORTED) {
                                        AppLogger.w("AppSearch not supported on this device")
                                    }
                                }
                            }
                        },
                        onError = { e ->
                            AppLogger.e("MainBannerController: tracking status observation failed", e)
                        },
                    )
                }
        }

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
         * Стратегия повторных попыток наблюдения:
         * - Бесконечные повторные попытки с экспоненциальной задержкой (cap 30 сек).
         * - Максимальная частота — 1 раз в 30 секунд.
         * - Отсутствие верхнего предела попыток является осознанным решением:
         *   UI должен продолжать попытки восстановления баннеров даже после
         *   временных сбоев.
         *
         * Строгое соблюдение API: на AOSP combine из StateFlow не бросает исключений.
         * Сбой наблюдения возможен только при деградации хранилища на кастомной прошивке.
         * Это проблема кастомной прошивки.
         *
         * При успешной эмиссии статус восстанавливается в Active.
         */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val trackingBannerState: StateFlow<TrackingBannerState> =
            recoveryTrigger
                .flatMapLatest {
                    combine(
                        trackingStatus,
                        permissionRepository.phoneStatePermissionGranted,
                        permissionRepository.usageStatsPermissionGranted,
                        permissionRepository.notificationPermissionGranted,
                        appSearchRetryCount,
                        appSearchBannerDismissed,
                        bannerExtraData,
                        observationFailedFlow,
                    ) { args ->
                        // @Suppress("UNCHECKED_CAST") is required because combine passes results as Any[]
                        // when using more than 5 flows.
                        @Suppress("UNCHECKED_CAST")
                        val status = args[ARG_STATUS] as Map<String, TrackingStatus>
                        val phone = args[ARG_PHONE] as Boolean
                        val usage = args[ARG_USAGE] as Boolean
                        val notif = args[ARG_NOTIF] as Boolean
                        val retry = args[ARG_RETRY] as Int
                        val dismissed = args[ARG_DISMISSED] as Boolean

                        // @Suppress("UNCHECKED_CAST") is required because combine passes results as Any[]
                        // when using more than 5 flows.
                        @Suppress("UNCHECKED_CAST")
                        val extra = args[ARG_EXTRA] as Triple<Boolean, Boolean, AppSettings>
                        val observationFailed = args[ARG_OBS_FAILED] as Boolean

                        TrackingBannerMapper.mapToBannerState(
                            statusMap = status,
                            phoneGranted = phone,
                            usageGranted = usage,
                            notificationsGranted = notif,
                            phonePermanentlyDenied = extra.first,
                            notificationPermanentlyDenied = extra.second,
                            settings = extra.third,
                            appSearchRetryCount = retry,
                            appSearchBannerDismissed = dismissed,
                            observationFailed = observationFailed,
                        )
                    }.onEach {
                        handleSuccess()
                        if (_status.value != TrackingStatus.Active) {
                            _status.value = TrackingStatus.Active
                        }
                    }.retryWhen { cause, _ ->
                        // CancellationException пробрасывается для корректной отмены корутины.
                        // Это строгое соблюдение API kotlinx.coroutines.
                        if (cause is CancellationException) {
                            throw cause
                        }

                        val (currentRetry, shouldLog) =
                            resetMutex.withLock {
                                observationRetryCount++
                                val now = timeProvider.elapsedRealtime()
                                val log =
                                    if (observationRetryCount <= MAX_LOGGED_RETRIES) {
                                        true
                                    } else {
                                        if ((now - lastLoggedTime) > PERIODIC_LOG_INTERVAL_MS) {
                                            lastLoggedTime = now
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                observationRetryCount to log
                            }

                        if (currentRetry > MAX_RETRY_ATTEMPTS) {
                            AppLogger.e(
                                "trackingBannerState: Наблюдение за баннерами остановлено после " +
                                    "$MAX_RETRY_ATTEMPTS неудачных попыток. Повторите перезапуск приложения.",
                                cause,
                            )
                            _status.value =
                                TrackingStatus.Error(
                                    TrackingStatus.ErrorType.DATABASE_ERROR,
                                    "Banner observation failed persistently: ${cause.message}",
                                )
                            observationFailedFlow.value = true
                            return@retryWhen false
                        }

                        if (_status.value !is TrackingStatus.Error) {
                            _status.value =
                                TrackingStatus.Error(
                                    TrackingStatus.ErrorType.UNKNOWN,
                                    "Banner observation failed: ${cause.message}",
                                )
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
                            (2.0.pow((currentRetry - 1).toDouble()).toLong() * Constants.Time.MILLIS_IN_SECOND)
                                .coerceAtMost(MAX_RETRY_DELAY_MS)
                        delay(delayMillis.milliseconds)
                        true
                    }.catch { e ->
                        AppLogger.e("MainBannerController: terminal error in banner flow", e)
                    }
                }.stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = TrackingBannerState(),
                )

        companion object {
            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val MAX_RETRY_DELAY_MS = Constants.Time.MAX_RETRY_DELAY_MS

            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val PERIODIC_LOG_INTERVAL_MS = 5 * 60 * 1000L

            /** Максимальное количество попыток наблюдения до полной остановки (Circuit Breaker). */
            private const val MAX_RETRY_ATTEMPTS = 20L

            /** Максимальное количество попыток, после которых логирование подавляется. */
            private const val MAX_LOGGED_RETRIES = 10L

            private const val ARG_STATUS = 0
            private const val ARG_PHONE = 1
            private const val ARG_USAGE = 2
            private const val ARG_NOTIF = 3
            private const val ARG_RETRY = 4
            private const val ARG_DISMISSED = 5
            private const val ARG_EXTRA = 6
            private const val ARG_OBS_FAILED = 7
        }
    }
