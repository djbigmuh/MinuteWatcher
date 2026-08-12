package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.NoOpLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun testBannerReactions() =
        runTest(UnconfinedTestDispatcher()) {
            val controller =
                MainBannerController(
                    trackingStatusRepository,
                    permissionRepository,
                    appSettingsRepository,
                    backgroundScope,
                )

            // With UnconfinedTestDispatcher and SharingStarted.Eagerly, it should have the value immediately
            assertEquals(false, controller.trackingBannerState.value.showUsageAccessBanner)

            usageStatsPermissionFlow.value = false
            // Updates should propagate immediately with Unconfined
            assertEquals(true, controller.trackingBannerState.value.showUsageAccessBanner)
        }

    @Test
    fun `retry delay for the first attempt is not zero`() =
        runTest {
            var collectionCount = 0
            val mockFlow = MutableStateFlow(true)
            val dynamicFlow =
                kotlinx.coroutines.flow.flow<Boolean> {
                    collectionCount++
                    if (collectionCount == 1) {
                        throw IOException("Temporary failure")
                    }
                    emitAll(mockFlow)
                }
            whenever(permissionRepository.usageStatsPermissionGranted).thenReturn(dynamicFlow)

            val startTime = testScheduler.currentTime
            MainBannerController(
                trackingStatusRepository,
                permissionRepository,
                appSettingsRepository,
                backgroundScope,
            )

            // With StandardTestDispatcher (default in runTest now), we advance time
            // Wait, runTest default dispatcher is NOT necessarily Standard if I don't specify it?
            // Actually, in modern kotlinx-coroutines-test, runTest uses a special dispatcher.

            testScheduler.advanceTimeBy(500)
            assertEquals("Should not have retried yet", 1, collectionCount)

            testScheduler.advanceTimeBy(500)
            // It should have retried now.
            // But we need to make sure the coroutine had a chance to run.
            testScheduler.runCurrent()
            assertEquals("Should have retried after 1000ms delay", 2, collectionCount)
            assertEquals(1000L, testScheduler.currentTime - startTime)
        }
}
