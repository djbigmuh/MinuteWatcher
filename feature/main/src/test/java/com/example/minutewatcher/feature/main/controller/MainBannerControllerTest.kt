package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.NoOpLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
            // (Initial value is TrackingBannerState, then it combine-fails, retries, and emits success)

            // Wait for retries (delay is 1000ms)
            testScheduler.advanceTimeBy(2000)
            testScheduler.advanceUntilIdle()

            val state = controller.trackingBannerState.value
            assertEquals(PermissionUiState.GRANTED, state.phonePermissionState)
        }

    @Test
    fun `trackingBannerState handles single flow error using individual catch`() =
        runTest {
            // Setup one flow to throw and NEVER recover
            whenever(permissionRepository.phoneStatePermissionGranted).thenReturn(
                flow {
                    error("Fatal failure")
                },
            )

            val controller =
                MainBannerController(
                    trackingStatusRepository,
                    permissionRepository,
                    appSettingsRepository,
                    backgroundScope,
                )

            testScheduler.advanceUntilIdle()

            // Individual catch { emit(false) } should handle this
            val state = controller.trackingBannerState.value
            // GRANTED=true, if caught it emits false -> NOT_GRANTED
            // (actually mapped based on settings, but not GRANTED)
            assertTrue(state.phonePermissionState != PermissionUiState.GRANTED)
        }
}
