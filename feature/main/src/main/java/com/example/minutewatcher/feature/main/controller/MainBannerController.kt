package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.catchingCancellation
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

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
        trackingStatusRepository: dagger.Lazy<TrackingStatusRepository>,
        permissionRepository: PermissionRepository,
        appSettingsRepository: AppSettingsRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) : TrackingStatusProvider {
        override val key: String = "banners"
        private val isPhonePermissionPermanentlyDenied = MutableStateFlow(value = false)

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

        private val isNotificationPermissionPermanentlyDenied = MutableStateFlow(value = false)

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

        private val recoveryTrigger = MutableStateFlow(0L)

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        override suspend fun recover() {
            initMutex.withLock {
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
                    ) { e ->
                        AppLogger.e("MainBannerController: tracking status observation failed", e)
                    }
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
                        bannerExtraData,
                    ) { snapshot, retry, dismissed, extra ->
                        TrackingBannerMapper.mapToBannerState(
                            statusMap = snapshot.statusMap,
                            phoneGranted = snapshot.phoneGranted,
                            usageGranted = snapshot.usageGranted,
                            notificationsGranted = snapshot.notificationsGranted,
                            phonePermanentlyDenied = extra.first,
                            notificationPermanentlyDenied = extra.second,
                            settings = extra.third,
                            appSearchRetryCount = retry,
                            appSearchBannerDismissed = dismissed,
                        )
                    }.onEach {
                        if (_status.value != TrackingStatus.Active) {
                            _status.value = TrackingStatus.Active
                        }
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
