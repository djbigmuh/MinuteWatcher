package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.NoOpLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.feature.main.TrackingBannerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun `trackingBannerState handles global failure when individual flow fails`() =
        runTest {
            // Setup observeAppSettings to throw
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

            testScheduler.advanceUntilIdle()

            // Global catch { emit(TrackingBannerState()) } should handle this
            val state = controller.trackingBannerState.value
            assertEquals(TrackingBannerState(), state)
            assertTrue(backgroundScope.isActive)
        }
}
