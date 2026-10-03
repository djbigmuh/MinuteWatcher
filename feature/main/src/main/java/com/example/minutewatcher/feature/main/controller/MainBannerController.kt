package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FlowObservationScope
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * Strict API compliance: on AOSP combine from StateFlow does not throw exceptions.
 *
 * Constructor note: trackingStatusRepository, permissionRepository,
 * appSettingsRepository, and seedRepository parameters are only used in property initializers
 * (inside combine/stateIn lambda expressions). Since they are captured by lambdas
 * as constructor parameter values and not used in class methods after initialization,
 * the 'val' modifier is removed to prevent creating unnecessary backing fields and save memory.
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
    ) {
        private val appSearchRetryCount = MutableStateFlow(value = 0)
        private val appSearchBannerDismissed = MutableStateFlow(value = false)

        /**
         * Increments the app search retry count.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun incrementAppSearchRetry() {
            appSearchRetryCount.update { it + 1 }
        }

        /**
         * Dismisses the app search error banner.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun dismissAppSearch() {
            appSearchBannerDismissed.value = true
        }

        @Volatile
        private var lastAppSearchErrorType: TrackingStatus.ErrorType? = null
        private val consecutiveRestartCount = AtomicInteger(0)

        private val isObservationStarted = AtomicBoolean(false)
        private val observationScope =
            FlowObservationScope<Map<TrackingKey, TrackingStatus>>(scope, "MainBannerController")

        val trackingStatus: StateFlow<Map<TrackingKey, TrackingStatus>> by lazy {
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
            val settings: AppSettings,
            val ephemeralActive: Boolean,
        )

        private data class BannerInputs(
            val statusMap: Map<TrackingKey, TrackingStatus>,
            val phoneGranted: Boolean,
            val usageGranted: Boolean,
            val notificationsGranted: Boolean,
            val retry: Int,
            val dismissed: Boolean,
            val settings: AppSettings,
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
                    consecutiveRestartCount.set(0)

                    val appsearch = statusMap[TrackingKey.APPSEARCH]
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
                if ((cause != null) && cause !is CancellationException) {
                    AppLogger.e("MainBannerController: Banner observation failed", cause)
                    isObservationStarted.set(false)
                    handleObservationFailure(cause)
                }
            }
        }

        private fun handleObservationFailure(cause: Throwable) {
            val count = consecutiveRestartCount.incrementAndGet()
            val delayMs = calculateRestartDelay(count)
            if (delayMs == null) {
                AppLogger.e(
                    "MainBannerController: Banner observation failed $count consecutive times. " +
                        "Stopping observation restarts.",
                    cause,
                )
                return
            }

            AppLogger.w(
                "MainBannerController: Banner observation failed (attempt $count/$MAX_RESTART_ATTEMPTS), " +
                    "scheduling restart in ${delayMs}ms",
                cause,
            )

            scope.launch {
                kotlinx.coroutines.delay(delayMs.milliseconds)
                ensureObservationStarted()
            }
        }

        internal companion object {
            const val MAX_RESTART_ATTEMPTS = 5
            const val MAX_RESTART_DELAY_MS = 30_000L

            internal fun calculateRestartDelay(attempt: Int): Long? {
                if (attempt > MAX_RESTART_ATTEMPTS) return null
                val exponent = (attempt - 1).coerceAtMost(30)
                val delayMs = com.example.minutewatcher.core.model.Constants.Flow.RETRY_DELAY_MS * (1L shl exponent)
                return delayMs.coerceAtMost(MAX_RESTART_DELAY_MS)
            }
        }
    }
