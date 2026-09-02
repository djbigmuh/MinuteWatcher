package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainBannerControllerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(kotlinx.coroutines.test.UnconfinedTestDispatcher())

    private val trackingStatusRepository: TrackingStatusRepository = mock()
    private val permissionRepository: PermissionRepository = mock()
    private val appSettingsRepository: com.example.minutewatcher.core.domain.AppSettingsRepository = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()

    private val phonePermissionFlow = MutableStateFlow(value = true)
    private val usageStatsPermissionFlow = MutableStateFlow(value = true)
    private val notificationPermissionFlow = MutableStateFlow(value = true)
    private val appSettingsFlow = MutableStateFlow(AppSettings(onboardingCompleted = true))
    private val trackingStatusFlow = MutableSharedFlow<Map<String, TrackingStatus>>(replay = 1)
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
    fun testBannerReactions() =
        runTest(mainDispatcherRule.testDispatcher) {
            val controller = createController(backgroundScope)

            // Start collecting to activate WhileSubscribed
            backgroundScope.launch {
                kotlin.runCatching {
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
    fun `recover cancels previous observation and starts exactly one new`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val controller = createController(backgroundScope)

            // Initial subscription in init
            backgroundScope.launch { controller.trackingBannerState.collect {} }
            advanceUntilIdle()
            assertEquals(1, trackingStatusFlow.subscriptionCount.value)

            // First recover call
            controller.recover()
            advanceUntilIdle()

            // Should still be 1 (old cancelled, new started)
            assertEquals(1, trackingStatusFlow.subscriptionCount.value)

            // Second recover call
            controller.recover()
            advanceUntilIdle()

            // Should still be 1
            assertEquals(1, trackingStatusFlow.subscriptionCount.value)
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
    fun `recover before initialization re-subscribes`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // We use a custom controller without automatic initialization to test recover explicitly
            // But MainBannerController calls setupObservation in init via ensureObservationStarted.
            // However, we can verify that recover() increases subscription count or re-triggers logic.

            val controller = createController(backgroundScope)
            advanceUntilIdle()

            // At this point, it's initialized and subscribed to trackingStatusRepository (count 1)
            assertEquals(1, trackingStatusFlow.subscriptionCount.value)

            // Force "never initialized" state by clearing isObservationStarted via reflection
            val startedField = MainBannerController::class.java.getDeclaredField("isObservationStarted")
            startedField.isAccessible = true
            val isStarted = startedField.get(controller) as java.util.concurrent.atomic.AtomicBoolean
            isStarted.set(false)

            // Act
            controller.recover()
            advanceUntilIdle()

            // Verify it re-subscribed (subscription count should be 1 as old one is replaced or re-added)
            assertEquals(1, trackingStatusFlow.subscriptionCount.value)
        }

    @Test
    fun `status transitions from Idle to Active during setupObservation`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val controller = createController(backgroundScope)

            // Start collecting trackingBannerState to trigger ensureObservationStarted if needed
            // although it is called in init.
            val collectJob =
                launch {
                    controller.trackingBannerState.collect {}
                }

            advanceUntilIdle()

            assertEquals(TrackingStatus.Active, controller.status.value)
            collectJob.cancel()
        }
}
