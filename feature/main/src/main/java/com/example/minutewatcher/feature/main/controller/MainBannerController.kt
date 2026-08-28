package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.catchingCancellation
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * MainBannerController manages the state of tracking banners (errors and notifications).
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [CancellationException].
 * **AOSP DataStore:** Fully compliant with AOSP DataStore semantics via observed flows.
 *
 * Строгое соблюдение API: на AOSP combine из StateFlow не бросает исключений.
 */
@Singleton
class MainBannerController
    @Inject
    constructor(
        trackingStatusRepository: TrackingStatusRepository,
        permissionRepository: PermissionRepository,
        appSettingsRepository: AppSettingsRepository,
        private val seedRepository: SimIdentitySeedRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val appSearchRetryCount = MutableStateFlow(value = 0)
        private val appSearchBannerDismissed = MutableStateFlow(value = false)

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

        private val initMutex = Mutex()
        private val isObservationStarted = AtomicBoolean(false)

    init {
        initialize()
    }

    private fun initialize() {
        ensureObservationStarted()
    }

        /**
         * Attempts to recover tracking observation.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        suspend fun recover() {
            initialize()
            val flow = lastTrackingStatusFlow
            if (flow == null) {
                AppLogger.w("MainBannerController: cannot recover, lastTrackingStatusFlow is null")
            } else {
                setupObservation(flow)
                isObservationStarted.set(true)
            }
            appSearchRetryCount.value = 0
            appSearchBannerDismissed.value = false
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

        private fun ensureObservationStarted() {
            if (isObservationStarted.get()) return
            scope.launch {
                if (isObservationStarted.compareAndSet(false, true)) {
                    setupObservation(trackingStatus)
                }
            }
        }

        private suspend fun setupObservation(statusFlow: StateFlow<Map<String, TrackingStatus>>) {
            initMutex.withLock {
                lastTrackingStatusFlow = statusFlow
                job?.cancel()
                val completed =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        job?.join()
                    }
                if (completed == null) {
                    AppLogger.w("MainBannerController: previous observation job did not terminate in time, cancelling")
                    job?.cancel()
                }
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
                        ) { e ->
                            AppLogger.e("MainBannerController: tracking status observation failed", e)
                        }
                    }
            }
        }

    private val appSettings = appSettingsRepository.observeAppSettings()

        /**
         * Состояние баннеров отслеживания.
         *
         * Строгое соблюдение API: на AOSP combine из StateFlow не бросает исключений.
         * Сбой наблюдения возможен только при деградации хранилища на кастомной прошивке.
         * Это проблема кастомной прошивки.
         *
         * При успешной эмиссии статус восстанавливается в Active.
         */
        val trackingBannerState: StateFlow<TrackingBannerState> =
            run {
                val permissionsFlow =
                    combine(
                        trackingStatus,
                        permissionRepository.phoneStatePermissionGranted,
                        permissionRepository.usageStatsPermissionGranted,
                        permissionRepository.notificationPermissionGranted,
                    ) { status, phone, usage, notif ->
                        PermissionsSnapshot(status, phone, usage, notif)
                    }

                combine(
                    permissionsFlow,
                    appSearchRetryCount,
                    appSearchBannerDismissed,
                    appSettings,
                    seedRepository.isEphemeralSeedActive,
                ) { snapshot, retry, dismissed, settings, ephemeralActive ->
                    TrackingBannerMapper.mapToBannerState(
                        statusMap = snapshot.statusMap,
                        phoneGranted = snapshot.phoneGranted,
                        usageGranted = snapshot.usageGranted,
                        notificationsGranted = snapshot.notificationsGranted,
                        settings = settings,
                        appSearchRetryCount = retry,
                        appSearchBannerDismissed = dismissed,
                        isStorageDegraded = ephemeralActive,
                    )
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = TrackingBannerState(),
            )

        private data class PermissionsSnapshot(
            val statusMap: Map<String, TrackingStatus>,
            val phoneGranted: Boolean,
            val usageGranted: Boolean,
            val notificationsGranted: Boolean,
        )
    }
