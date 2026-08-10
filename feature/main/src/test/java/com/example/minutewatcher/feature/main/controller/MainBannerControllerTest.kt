package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.NoOpLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.feature.main.TrackingBannerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainBannerControllerTest {
    private val trackingStatusRepository: TrackingStatusRepository = mock()
    private val permissionRepository: PermissionRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()

    private val phonePermissionFlow = MutableStateFlow(true)
    private val usageStatsPermissionFlow = MutableStateFlow(true)
    private val notificationPermissionFlow = MutableStateFlow(true)
    private val appSettingsFlow = MutableStateFlow(AppSettings())
    private val trackingStatusFlow =
        MutableStateFlow(emptyMap<String, com.example.minutewatcher.core.model.TrackingStatus>())

    @Before
    fun setup() {
        AppLogger.init(mock(), NoOpLogger())
        whenever(permissionRepository.phoneStatePermissionGranted).thenReturn(phonePermissionFlow)
        whenever(permissionRepository.usageStatsPermissionGranted).thenReturn(usageStatsPermissionFlow)
        whenever(permissionRepository.notificationPermissionGranted).thenReturn(notificationPermissionFlow)
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(appSettingsFlow)
        whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(trackingStatusFlow)
    }

    @Test
    fun `trackingBannerState recovers after error in permission flow`() =
        runTest {
            // 1. Setup a flow that fails then succeeds
            var shouldFail = true
            val failingPhoneFlow =
                flow {
                    if (shouldFail) {
                        shouldFail = false
                        error("Temporary failure")
                    }
                    emit(true)
                }
            whenever(permissionRepository.phoneStatePermissionGranted).thenReturn(failingPhoneFlow)

            val controller =
                MainBannerController(
                    trackingStatusRepository,
                    permissionRepository,
                    appSettingsRepository,
                    backgroundScope,
                )

            // 2. The flow should retry and eventually succeed
            // Wait for retries (delay is 1000ms)
            testScheduler.advanceTimeBy(2000)
            testScheduler.advanceUntilIdle()

            val state = controller.trackingBannerState.value
            assertEquals(PermissionUiState.GRANTED, state.phonePermissionState)
            assertTrue(backgroundScope.isActive)
        }

    @Test
    fun `trackingBannerState handles global failure when individual flow fails completely`() =
        runTest {
            // Setup observeAppSettings to throw and NEVER recover
            val failingFlow =
                object : StateFlow<AppSettings> {
                    override val replayCache: List<AppSettings>
                        get() = listOf(AppSettings())

                    override val value: AppSettings
                        get() = AppSettings()

                    override suspend fun collect(
                        collector: kotlinx.coroutines.flow.FlowCollector<AppSettings>,
                    ): Nothing = error("Fatal settings failure")
                }
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(failingFlow)

            val controller =
                MainBannerController(
                    trackingStatusRepository,
                    permissionRepository,
                    appSettingsRepository,
                    backgroundScope,
                )

            // Wait for all retries (5 attempts * 1000ms = 5000ms)
            testScheduler.advanceTimeBy(10000)
            testScheduler.advanceUntilIdle()

            // Global catch { emit(TrackingBannerState()) } should handle this
            val state = controller.trackingBannerState.value
            assertEquals(TrackingBannerState(), state)
            assertTrue(backgroundScope.isActive)
        }

    @Test
    fun `trackingBannerState recovers after bannerExtraData failure`() =
        runTest {
            // 1. Setup a flow that fails then succeeds
            var collectCount = 0
            val successSettings = AppSettings(onboardingCompleted = true)
            // Use usageStatsPermissionFlow = false to see the banner if onboarding is completed
            usageStatsPermissionFlow.value = false

            val failingSettingsFlow =
                object : StateFlow<AppSettings> {
                    override val replayCache: List<AppSettings> get() = listOf(successSettings)
                    override val value: AppSettings get() = successSettings

                    override suspend fun collect(
                        collector: kotlinx.coroutines.flow.FlowCollector<AppSettings>,
                    ): Nothing {
                        collectCount++
                        if (collectCount == 1) {
                            error("Temporary failure")
                        }
                        collector.emit(successSettings)
                        awaitCancellation()
                    }
                }
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(failingSettingsFlow)

            val controller =
                MainBannerController(
                    trackingStatusRepository,
                    permissionRepository,
                    appSettingsRepository,
                    backgroundScope,
                )

            // 2. Wait for processing.
            // Initially it should be the initialValue (empty state)
            assertEquals(TrackingBannerState(), controller.trackingBannerState.value)

            // Wait for retry (delay is 1000ms)
            testScheduler.advanceTimeBy(2000)
            testScheduler.advanceUntilIdle()

            // Now it should be recovered
            val stateAfterRecovery = controller.trackingBannerState.value
            // If recovered, onboardingCompleted = true => showUsageAccessBanner = true
            assertEquals(true, stateAfterRecovery.showUsageAccessBanner)
        }
}
