package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelBannerTest : MainViewModelTestFixtures() {
    @Test
    fun `trackingBannerState shows phone permission banner when granted is false`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()

            phonePermissionFlow.value = false
            advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState != PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState hides phone permission banner when granted is true`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()

            phonePermissionFlow.value = true
            usageStatsPermissionFlow.value = true
            advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState reflects permanently denied status`() =
        runTest(testDispatcher) {
            val settingsFlow =
                MutableStateFlow(
                    AppSettings(
                        wasPhonePermissionRequestedBefore = true,
                        onboardingCompleted = true,
                    ),
                )
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            phonePermissionFlow.value = false

            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()
            advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            // Since phonePermissionFlow.value = false and wasPhonePermissionRequestedBefore = true,
            // it's derived as PERMANENTLY_DENIED now internally in mapper.
            assertTrue(bannerState.phonePermissionState == PermissionUiState.PERMANENTLY_DENIED)
        }

    @Test
    fun `trackingBannerState shows notification banner when alerts enabled but not granted`() =
        runTest(testDispatcher) {
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
            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()
            advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.notificationPermissionState != PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState hides notification banner when granted even if alerts enabled`() =
        runTest(testDispatcher) {
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
            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()
            advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.notificationPermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState correctly identifies NOT_REQUESTED vs DENIED states`() =
        runTest(testDispatcher) {
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
            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()
            advanceUntilIdle()

            var bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState == PermissionUiState.NOT_REQUESTED)

            // Case 2: Requested but denied
            settingsFlow.value =
                AppSettings(
                    wasPhonePermissionRequestedBefore = true,
                    onboardingCompleted = true,
                )
            advanceUntilIdle()

            bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState == PermissionUiState.PERMANENTLY_DENIED)
        }

    @Test
    fun `trackingBannerState hides notification banner when alerts disabled`() =
        runTest(testDispatcher) {
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
            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()
            advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.notificationPermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState shows usage access banner when permission missing`() =
        runTest(testDispatcher) {
            usageStatsPermissionFlow.value = false

            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue("Banner should be visible when permission missing", bannerState.showUsageAccessBanner)
        }

    @Test
    fun `trackingBannerState shows traffic error banner when other tracking error occurs`() =
        runTest(testDispatcher) {
            val trackingFlow =
                MutableStateFlow(
                    mapOf(
                        TrackingKey.TRAFFIC to
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
            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.showTrafficErrorBanner)
            assertFalse(bannerState.showUsageAccessBanner)
        }

    @Test
    fun `trackingBannerState shows shortcuts error banner when shortcuts error occurs`() =
        runTest(testDispatcher) {
            val trackingFlow =
                MutableStateFlow(
                    mapOf(
                        TrackingKey.SHORTCUTS to
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.UNKNOWN,
                                "Some error",
                            ) as TrackingStatus,
                    ),
                )
            whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(trackingFlow)
            usageStatsPermissionFlow.value = true

            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.showShortcutsErrorBanner)
        }

    @Test
    fun `notificationPermissionState is GRANTED when notificationsGranted is true`() =
        runTest(testDispatcher) {
            val settingsFlow =
                MutableStateFlow(
                    AppSettings(
                        notifyLowMinutes = true,
                        onboardingCompleted = true,
                    ),
                )
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            notificationPermissionFlow.value = true

            val viewModel = createViewModel(backgroundScope)

            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()
            advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.notificationPermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `notificationPermissionState is GRANTED when alerts are disabled and not granted`() =
        runTest(testDispatcher) {
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

            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()
            advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.notificationPermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `notificationPermissionState is PERMANENTLY_DENIED when alerts enabled and requested but not granted`() =
        runTest(testDispatcher) {
            val settingsFlow =
                MutableStateFlow(
                    AppSettings(
                        notifyLowMinutes = true,
                        notificationPermissionRequested = true,
                        onboardingCompleted = true,
                    ),
                )
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            notificationPermissionFlow.value = false

            val viewModel = createViewModel(backgroundScope)

            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()
            advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.notificationPermissionState == PermissionUiState.PERMANENTLY_DENIED)
        }

    @Test
    fun `notificationPermissionState is NOT_REQUESTED when alerts enabled but not yet requested`() =
        runTest(testDispatcher) {
            val settingsFlow =
                MutableStateFlow(
                    AppSettings(
                        notifyLowMinutes = true,
                        notificationPermissionRequested = false,
                        onboardingCompleted = true,
                    ),
                )
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            notificationPermissionFlow.value = false

            val viewModel = createViewModel(backgroundScope)

            backgroundScope.launch {
                viewModel.trackingBannerState.collect {}
            }
            runCurrent()
            advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.notificationPermissionState == PermissionUiState.NOT_REQUESTED)
        }
}
