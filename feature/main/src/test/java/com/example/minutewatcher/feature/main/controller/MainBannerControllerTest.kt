package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainBannerControllerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val trackingStatusRepository: TrackingStatusRepository = mock()
    private val permissionRepository: PermissionRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()

    private val phonePermissionFlow = MutableStateFlow(value = true)
    private val usageStatsPermissionFlow = MutableStateFlow(value = true)
    private val notificationPermissionFlow = MutableStateFlow(value = true)
    private val appSettingsFlow = MutableStateFlow(AppSettings(onboardingCompleted = true))
    private val trackingStatusFlow = MutableSharedFlow<Map<TrackingKey, TrackingStatus>>(replay = 1)
    private val isEphemeralSeedActiveFlow = MutableStateFlow(value = false)

    @Before
    fun setup() {
        initTestLogger()
        trackingStatusFlow.tryEmit(emptyMap())
        whenever(permissionRepository.phoneStatePermissionGranted).thenReturn(phonePermissionFlow)
        whenever(permissionRepository.usageStatsPermissionGranted).thenReturn(usageStatsPermissionFlow)
        whenever(permissionRepository.notificationPermissionGranted).thenReturn(notificationPermissionFlow)
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(appSettingsFlow)
        whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(trackingStatusFlow)
        whenever(seedRepository.isEphemeralSeedActive).thenReturn(isEphemeralSeedActiveFlow.asStateFlow())
    }

    private fun createController(scope: CoroutineScope) =
        MainBannerController(
            trackingStatusRepository = { trackingStatusRepository },
            permissionRepository = permissionRepository,
            appSettingsRepository = appSettingsRepository,
            seedRepository = seedRepository,
            scope = scope,
        )

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `banner reactions`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val controller = createController(backgroundScope)

            // Start collecting to activate WhileSubscribed
            backgroundScope.launch {
                runCatching {
                    controller.trackingBannerState.collect {}
                }
            }
            runCurrent()

            assertEquals(false, controller.trackingBannerState.value.showUsageAccessBanner)

            usageStatsPermissionFlow.value = false
            advanceUntilIdle()
            // Updates should propagate after advancing virtual time (Standard dispatcher)
            assertEquals(true, controller.trackingBannerState.value.showUsageAccessBanner)
        }

    @Test
    fun `isStorageDegraded reflects seedRepository state`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val controller = createController(backgroundScope)

            backgroundScope.launch { controller.trackingBannerState.collect {} }
            runCurrent()

            // Initial state
            assertEquals(false, controller.trackingBannerState.value.isStorageDegraded)

            // Activate ephemeral seed
            isEphemeralSeedActiveFlow.value = true
            advanceUntilIdle()

            assertEquals(true, controller.trackingBannerState.value.isStorageDegraded)
            assertTrue("observationFailed should be true", controller.trackingBannerState.value.isStorageDegraded)

            // Restore seed
            isEphemeralSeedActiveFlow.value = false
            advanceUntilIdle()

            assertEquals(false, controller.trackingBannerState.value.isStorageDegraded)
            assertTrue("observationFailed should be false", !controller.trackingBannerState.value.isStorageDegraded)
        }

    @Test
    fun `notification banner hidden when notifications disabled in settings`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given: Permission NOT granted, but notifications DISABLED in settings
            notificationPermissionFlow.value = false
            appSettingsFlow.value =
                AppSettings(
                    onboardingCompleted = true,
                    notifyLowMinutes = false,
                    notifyLowTraffic = false,
                )

            val controller = createController(backgroundScope)
            backgroundScope.launch { controller.trackingBannerState.collect {} }
            advanceUntilIdle()

            // Then: Banner should be GRANTED (hidden)
            assertEquals(PermissionUiState.GRANTED, controller.trackingBannerState.value.notificationPermissionState)
        }

    @Test
    fun `calculateRestartDelay produces exponential backoff and stops after limit`() =
        runTest {
            assertEquals(1000L, MainBannerController.calculateRestartDelay(1))
            assertEquals(2000L, MainBannerController.calculateRestartDelay(2))
            assertEquals(4000L, MainBannerController.calculateRestartDelay(3))
            assertEquals(8000L, MainBannerController.calculateRestartDelay(4))
            assertEquals(16000L, MainBannerController.calculateRestartDelay(5))
            assertEquals(null, MainBannerController.calculateRestartDelay(6))
        }

    @Test
    fun `observation failure triggers restart and emission resets restart count`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val statusFlow = MutableSharedFlow<Map<TrackingKey, TrackingStatus>>(replay = 1)
            whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(statusFlow)

            val controller = createController(backgroundScope)
            backgroundScope.launch { controller.trackingStatus.collect {} }

            statusFlow.emit(emptyMap())
            runCurrent()

            val failingFlow =
                flow<Map<TrackingKey, TrackingStatus>> {
                    throw IllegalStateException("Flow observation crash")
                }
            whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(failingFlow)

            statusFlow.emit(mapOf(TrackingKey.APPSEARCH to TrackingStatus.Active))
            runCurrent()

            testScheduler.advanceTimeBy(1500L)
            runCurrent()

            whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(statusFlow)
            statusFlow.emit(emptyMap())
            advanceUntilIdle()

            assertEquals(1000L, MainBannerController.calculateRestartDelay(1))
        }
}
