package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.feature.main.TrackingBannerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainBannerControllerTest {
    private val trackingStatusRepository: TrackingStatusRepository = mock()
    private val permissionRepository: PermissionRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()

    private val phonePermissionFlow = MutableStateFlow(true)
    private val usageStatsPermissionFlow = MutableStateFlow(true)
    private val notificationPermissionFlow = MutableStateFlow(true)
    private val appSettingsFlow = MutableStateFlow(AppSettings(onboardingCompleted = true))
    private val trackingStatusFlow =
        MutableStateFlow(emptyMap<String, com.example.minutewatcher.core.model.TrackingStatus>())

    private val timeProvider = FixedTimeProvider(currentTime = 1000000L, currentRealtime = 1000000L)

    @Before
    fun setup() {
        initTestLogger()
        whenever(permissionRepository.phoneStatePermissionGranted).thenReturn(phonePermissionFlow)
        whenever(permissionRepository.usageStatsPermissionGranted).thenReturn(usageStatsPermissionFlow)
        whenever(permissionRepository.notificationPermissionGranted).thenReturn(notificationPermissionFlow)
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(appSettingsFlow)
        whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(trackingStatusFlow)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun testBannerReactions() =
        runTest(UnconfinedTestDispatcher()) {
            val controller =
                MainBannerController(
                    trackingStatusRepository,
                    permissionRepository,
                    appSettingsRepository,
                    backgroundScope,
                    timeProvider,
                )

            // Start collecting to activate WhileSubscribed
            backgroundScope.launch { controller.trackingBannerState.collect {} }

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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `retry delay for the first attempt is not zero`() =
        runTest {
            var collectionCount = 0

            // Flow передаётся в mock и используется внутри контроллера через подписку.
            val dynamicFlow =
                kotlinx.coroutines.flow.flow<Boolean> {
                    collectionCount++
                    if (collectionCount == 1) {
                        throw IOException("Temporary failure")
                    }
                    emit(true)
                }
            whenever(permissionRepository.usageStatsPermissionGranted).thenReturn(dynamicFlow)

            val controller =
                MainBannerController(
                    trackingStatusRepository,
                    permissionRepository,
                    appSettingsRepository,
                    backgroundScope,
                    timeProvider,
                )

            backgroundScope.launch { controller.trackingBannerState.collect {} }
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(500)
            assertEquals("Should not have retried yet", 1, collectionCount)

            val statusBeforeRetry = controller.trackingBannerState.value
            assertTrue(statusBeforeRetry is TrackingBannerState)
            assertEquals(false, statusBeforeRetry.showUsageAccessBanner)

            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()

            val expectedDelay = 1000L.coerceAtMost(MainBannerController.MAX_RETRY_DELAY_MS)
            val statusAfterRetry = controller.trackingBannerState.value
            assertEquals("Should have retried after ${expectedDelay}ms delay", 2, collectionCount)

            assertEquals(false, statusAfterRetry.showUsageAccessBanner)
        }
}
