package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun testBannerReactions() =
        runTest(UnconfinedTestDispatcher()) {
            val controller =
                MainBannerController(
                    trackingStatusRepository = { trackingStatusRepository },
                    permissionRepository,
                    appSettingsRepository,
                    seedRepository,
                    backgroundScope,
                )

            // Start collecting to activate WhileSubscribed
            backgroundScope.launch {
                kotlin.runCatching {
                    controller.trackingBannerState.collect {}
                }
            }

            // With UnconfinedTestDispatcher and SharingStarted.Eagerly (now WhileSubscribed),
            // we might need a small advance or runCurrent to ensure collection started.
            testScheduler.runCurrent()

            assertEquals(false, controller.trackingBannerState.value.showUsageAccessBanner)

            usageStatsPermissionFlow.value = false
            testScheduler.runCurrent()
            // Updates should propagate immediately with Unconfined
            assertEquals(true, controller.trackingBannerState.value.showUsageAccessBanner)
        }

    @Test
    fun `recover cancels previous observation and starts exactly one new`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller =
                MainBannerController(
                    trackingStatusRepository = { trackingStatusRepository },
                    permissionRepository,
                    appSettingsRepository,
                    seedRepository,
                    backgroundScope,
                )

            // Initial subscription in init
            testScheduler.advanceUntilIdle()
            assertEquals(1, trackingStatusFlow.subscriptionCount.value)

            // First recover call
            controller.recover()
            testScheduler.advanceUntilIdle()

            // Should still be 1 (old cancelled, new started)
            assertEquals(1, trackingStatusFlow.subscriptionCount.value)

            // Second recover call
            controller.recover()
            testScheduler.advanceUntilIdle()

            // Should still be 1
            assertEquals(1, trackingStatusFlow.subscriptionCount.value)
        }

    @Test
    fun `isStorageDegraded reflects seedRepository state`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller =
                MainBannerController(
                    trackingStatusRepository = { trackingStatusRepository },
                    permissionRepository,
                    appSettingsRepository,
                    seedRepository,
                    backgroundScope,
                )

            // Initial state
            assertEquals(false, controller.trackingBannerState.value.isStorageDegraded)

            // Activate ephemeral seed
            isEphemeralSeedActiveFlow.value = true
            testScheduler.runCurrent()

            assertEquals(true, controller.trackingBannerState.value.isStorageDegraded)
            val status = controller.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)

            // Restore seed
            isEphemeralSeedActiveFlow.value = false
            testScheduler.runCurrent()

            assertEquals(false, controller.trackingBannerState.value.isStorageDegraded)
            assertEquals(TrackingStatus.Active, controller.status.value)
        }
}
