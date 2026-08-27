package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelBannerTest : MainViewModelTestFixtures() {
    @Test
    fun `trackingBannerState shows phone permission banner when granted is false`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }

            phonePermissionFlow.value = false
            testScheduler.advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState != PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState hides phone permission banner when granted is true`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }

            phonePermissionFlow.value = true
            usageStatsPermissionFlow.value = true
            testScheduler.advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState reflects permanently denied status`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }

            viewModel.updatePermissionDeniedState(phone = true)
            testScheduler.advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState == PermissionUiState.PERMANENTLY_DENIED)
        }

    @Test
    fun `trackingBannerState shows notification banner when alerts enabled but not granted`() =
        testScope.runTest {
            val settingsFlow =
                MutableStateFlow(
                    AppSettings(
                        notifyLowMinutes = true,
                        notifyLowTraffic = false,
                        onboardingCompleted = true,
                    ),
                )
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            notificationPermissionFlow.value = false

            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }
            testScheduler.advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.notificationPermissionState != PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState hides notification banner when granted even if alerts enabled`() =
        testScope.runTest {
            val settingsFlow =
                MutableStateFlow(
                    AppSettings(
                        notifyLowMinutes = true,
                        notifyLowTraffic = true,
                        onboardingCompleted = true,
                    ),
                )
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            notificationPermissionFlow.value = true

            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }
            testScheduler.advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.notificationPermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState correctly identifies NOT_REQUESTED vs DENIED states`() =
        testScope.runTest {
            // Case 1: Not requested
            val settingsFlow =
                MutableStateFlow(
                    AppSettings(
                        wasPhonePermissionRequestedBefore = false,
                        onboardingCompleted = true,
                    ),
                )
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            phonePermissionFlow.value = false

            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }
            testScheduler.advanceUntilIdle()

            var bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState == PermissionUiState.NOT_REQUESTED)

            // Case 2: Requested but denied
            settingsFlow.value =
                AppSettings(
                    wasPhonePermissionRequestedBefore = true,
                    onboardingCompleted = true,
                )
            testScheduler.advanceUntilIdle()

            bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState == PermissionUiState.DENIED)
        }

    @Test
    fun `trackingBannerState hides notification banner when alerts disabled`() =
        testScope.runTest {
            val settingsFlow =
                MutableStateFlow(
                    AppSettings(
                        notifyLowMinutes = false,
                        notifyLowTraffic = false,
                        onboardingCompleted = true,
                    ),
                )
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            notificationPermissionFlow.value = false

            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }
            testScheduler.advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.notificationPermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState shows usage access banner when permission missing`() =
        testScope.runTest {
            usageStatsPermissionFlow.value = false

            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }

            val bannerState = viewModel.trackingBannerState.value
            assertTrue("Banner should be visible when permission missing", bannerState.showUsageAccessBanner)
        }

    @Test
    fun `trackingBannerState shows traffic error banner when other tracking error occurs`() =
        testScope.runTest {
            val trackingFlow =
                MutableStateFlow(
                    mapOf(
                        "traffic" to
                            com.example.minutewatcher.core.model.TrackingStatus.Error(
                                com.example.minutewatcher.core.model.TrackingStatus.ErrorType.UNKNOWN,
                                "Some error",
                            ),
                    ),
                )
            whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(trackingFlow)
            usageStatsPermissionFlow.value = true

            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.showTrafficErrorBanner)
            assertFalse(bannerState.showUsageAccessBanner)
        }

    @Test
    fun `trackingBannerState shows shortcuts error banner when shortcuts error occurs`() =
        testScope.runTest {
            val trackingFlow =
                MutableStateFlow(
                    mapOf(
                        "shortcuts" to
                            com.example.minutewatcher.core.model.TrackingStatus.Error(
                                com.example.minutewatcher.core.model.TrackingStatus.ErrorType.UNKNOWN,
                                "Some error",
                            ),
                    ),
                )
            whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(trackingFlow)
            usageStatsPermissionFlow.value = true

            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.showShortcutsErrorBanner)
        }
}
