package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FlowObservationScope
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
 *
 * Примечание к конструктору: параметры trackingStatusRepository, permissionRepository,
 * appSettingsRepository и seedRepository используются только в инициализаторах свойств
 * (внутри лямбда-выражений combine/stateIn). Поскольку они захватываются лямбдами
 * как значения параметров конструктора и не используются в методах класса после инициализации,
 * модификатор 'val' удален для предотвращения создания ненужных backing fields и экономии памяти.
 */
@Singleton
class MainBannerController
@Inject
constructor(
    trackingStatusRepository: Lazy<TrackingStatusRepository>,
    permissionRepository: PermissionRepository,
    appSettingsRepository: AppSettingsRepository,
    seedRepository: SimIdentitySeedRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : TrackingStatusProvider {
    override val key: String = "banners"

    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

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

    private val isObservationStarted = AtomicBoolean(false)
    private val observationScope = FlowObservationScope<Map<String, TrackingStatus>>(scope, "MainBannerController")

    val trackingStatus: StateFlow<Map<String, TrackingStatus>> by lazy {
        trackingStatusRepository
            .get()
            .observeTrackingStatus()
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap(),
            )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val trackingBannerState: StateFlow<TrackingBannerState> by lazy {
        combine(
            trackingStatus,
            permissionRepository.phoneStatePermissionGranted,
            permissionRepository.usageStatsPermissionGranted,
            permissionRepository.notificationPermissionGranted,
            combine(
                appSearchRetryCount,
                appSearchBannerDismissed,
                appSettingsRepository.observeAppSettings(),
                seedRepository.isEphemeralSeedActive,
            ) { retry, dismissed, settings, ephemeralActive ->
                BannerFlags(retry, dismissed, settings, ephemeralActive)
            },
        ) { statusMap, phone, usage, notifications, flags ->
            BannerInputs(
                statusMap = statusMap,
                phoneGranted = phone,
                usageGranted = usage,
                notificationsGranted = notifications,
                retry = flags.retry,
                dismissed = flags.dismissed,
                settings = flags.settings,
                ephemeralActive = flags.ephemeralActive,
            )
        }.map { data ->
            TrackingBannerMapper.mapToBannerState(
                statusMap = data.statusMap,
                phoneGranted = data.phoneGranted,
                usageGranted = data.usageGranted,
                notificationsGranted = data.notificationsGranted,
                settings = data.settings,
                appSearchRetryCount = data.retry,
                appSearchBannerDismissed = data.dismissed,
                isStorageDegraded = data.ephemeralActive,
            )
        }.distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = TrackingBannerState(),
            )
    }

    private data class BannerFlags(
        val retry: Int,
        val dismissed: Boolean,
        val settings: com.example.minutewatcher.core.model.AppSettings,
        val ephemeralActive: Boolean,
    )

    private data class BannerInputs(
        val statusMap: Map<String, TrackingStatus>,
        val phoneGranted: Boolean,
        val usageGranted: Boolean,
        val notificationsGranted: Boolean,
        val retry: Int,
        val dismissed: Boolean,
        val settings: com.example.minutewatcher.core.model.AppSettings,
        val ephemeralActive: Boolean,
    )

    init {
        ensureObservationStarted()
    }

    private fun ensureObservationStarted() {
        if (isObservationStarted.get()) return
        scope.launch {
            if (isObservationStarted.compareAndSet(false, true)) {
                try {
                    setupObservation()
                } catch (e: CancellationException) {
                    isObservationStarted.set(false)
                    throw e
                } catch (e: Exception) {
                    isObservationStarted.set(false)
                    AppLogger.e("MainBannerController: Failed to setup observation", e)
                }
            }
        }
    }

    private suspend fun setupObservation() {
        val job =
            observationScope.observe(trackingStatus) { statusMap ->
                currentCoroutineContext().ensureActive()

                // Update _status based on banner errors
                val hasError = statusMap.values.any { it is TrackingStatus.Error }
                _status.value =
                    if (hasError) {
                        TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Banner errors detected")
                    } else {
                        TrackingStatus.Active
                    }

                val appsearch = statusMap["appsearch"]
                val currentErrorType = (appsearch as? TrackingStatus.Error)?.type
                if (currentErrorType != lastAppSearchErrorType) {
                    lastAppSearchErrorType = currentErrorType
                    appSearchRetryCount.value = 0
                    appSearchBannerDismissed.value = false
                    if (currentErrorType == TrackingStatus.ErrorType.NOT_SUPPORTED) {
                        AppLogger.w("MainBannerController: AppSearch not supported on this device")
                    }
                }
            }

        job.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                AppLogger.e("MainBannerController: Banner observation failed", cause)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Banner observation failed: ${cause.message}",
                    )
                isObservationStarted.set(false)
            }
        }
    }

    /**
     * Attempts to recover tracking observation.
     *
     * **Thread Safety:** Safe to call from any coroutine context.
     */
    override suspend fun recover() {
        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
            observationScope.getJob()?.cancelAndJoin()
        }
        observationScope.shutdown()
        isObservationStarted.set(false)
        appSearchRetryCount.value = 0
        appSearchBannerDismissed.value = false
        _status.value = TrackingStatus.Idle
        ensureObservationStarted()
    }
}
