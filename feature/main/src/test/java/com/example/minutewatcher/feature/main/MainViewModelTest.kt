package com.example.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.feature.main.controller.MainViewModelControllers
import com.example.minutewatcher.feature.main.controller.TrackingRecoveryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val trackingStatusRepository: TrackingStatusRepository = mock()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val settingsUseCase: SettingsUseCase = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val permissionRepository: PermissionRepository = mock()
    private val trackingRecoveryController: TrackingRecoveryController = mock()
    private val savedStateHandle = SavedStateHandle()

    private val testDispatcher = UnconfinedTestDispatcher()

    private val phonePermissionFlow = MutableStateFlow(false)
    private val usageStatsPermissionFlow = MutableStateFlow(false)
    private val notificationPermissionFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        phonePermissionFlow.value = false
        usageStatsPermissionFlow.value = false
        notificationPermissionFlow.value = false

        whenever(permissionRepository.phoneStatePermissionGranted).thenReturn(phonePermissionFlow)
        whenever(permissionRepository.usageStatsPermissionGranted).thenReturn(usageStatsPermissionFlow)
        whenever(permissionRepository.notificationPermissionGranted).thenReturn(notificationPermissionFlow)

        whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(MutableStateFlow(emptyMap()))
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(MutableStateFlow(emptyList()))
        whenever(usageStateRepository.observeSnapshot()).thenReturn(MutableStateFlow(mock()))
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings()))
        whenever(settingsUseCase.appTheme).thenReturn(MutableStateFlow(AppTheme.FOLLOW_SYSTEM))
        whenever(settingsUseCase.appLanguage).thenReturn(MutableStateFlow(null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(scope: CoroutineScope): MainViewModel {
        val dialogController =
            com.example.minutewatcher.feature.main.controller
                .MainDialogController(savedStateHandle)
        val bannerController =
            com.example.minutewatcher.feature.main.controller.MainBannerController(
                trackingStatusRepository = trackingStatusRepository,
                permissionRepository = permissionRepository,
                appSettingsRepository = appSettingsRepository,
                scope = scope,
            )
        val refreshController =
            com.example.minutewatcher.feature.main.controller.MainSimRefreshController(
                usageUseCase,
                usageStateRepository,
                testDispatcher,
            )
        val settingsController =
            com.example.minutewatcher.feature.main.controller.MainSettingsController(
                appSettingsRepository = appSettingsRepository,
                settingsUseCase = settingsUseCase,
                scope = scope,
            )
        val simDataController =
            com.example.minutewatcher.feature.main.controller.MainSimDataController(
                usageStateRepository = usageStateRepository,
                trackingRecoveryController = trackingRecoveryController,
                simIdentityRepository = simIdentityRepository,
                simLimitsRepository = simLimitsRepository,
                usageUseCase = usageUseCase,
                dataChangeNotifier = dataChangeNotifier,
                ioDispatcher = testDispatcher,
                scope = scope,
            )

        val controllers =
            MainViewModelControllers(
                dialog = dialogController,
                banner = bannerController,
                refresh = refreshController,
                settings = settingsController,
                simData = simDataController,
            )

        return MainViewModel(
            controllers = controllers,
            appSettingsRepository = appSettingsRepository,
            autoResetUseCase = autoResetUseCase,
            savedStateHandle = savedStateHandle,
            ioDispatcher = testDispatcher,
        )
    }

    @Test
    fun `trackingBannerState shows phone permission banner when granted is false`() =
        runTest(testDispatcher) {
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
        runTest(testDispatcher) {
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
        runTest(testDispatcher) {
            val viewModel = createViewModel(backgroundScope)

            // Collect to trigger WhileSubscribed
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.trackingBannerState.collect {}
            }

            viewModel.setPhonePermissionPermanentlyDenied(true)
            testScheduler.advanceUntilIdle()

            val bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState == PermissionUiState.PERMANENTLY_DENIED)
        }

    @Test
    fun `trackingBannerState shows notification banner when alerts enabled but not granted`() =
        runTest(testDispatcher) {
            val settingsFlow = MutableStateFlow(AppSettings(notifyLowMinutes = true, notifyLowTraffic = false))
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
        runTest(testDispatcher) {
            val settingsFlow = MutableStateFlow(AppSettings(notifyLowMinutes = true, notifyLowTraffic = true))
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
        runTest(testDispatcher) {
            // Case 1: Not requested
            val settingsFlow = MutableStateFlow(AppSettings(phonePermissionRequested = false))
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
            settingsFlow.value = AppSettings(phonePermissionRequested = true)
            testScheduler.advanceUntilIdle()

            bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState == PermissionUiState.DENIED)
        }

    @Test
    fun `trackingBannerState hides notification banner when alerts disabled`() =
        runTest(testDispatcher) {
            val settingsFlow = MutableStateFlow(AppSettings(notifyLowMinutes = false, notifyLowTraffic = false))
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
        runTest(testDispatcher) {
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
        runTest(testDispatcher) {
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
        runTest(testDispatcher) {
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

    @Test
    fun `setTheme calls settingsUseCase`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(backgroundScope)
            val theme = AppTheme.DARK

            viewModel.setTheme(theme)
            runCurrent()
            advanceUntilIdle()

            org.mockito.kotlin
                .verify(settingsUseCase)
                .setTheme(theme)
        }

    @Test
    fun `onActivityResume triggers refresh on reboot (negative elapsed)`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle() // Даем initializeApp() завершиться
            org.mockito.kotlin.clearInvocations(usageStateRepository)

            // ВАЖНО: Устанавливаем симуляцию состояния "до перезагрузки" ПОСЛЕ initializeApp()
            savedStateHandle["last_refresh_at"] = 500_000L

            // nowElapsed = 1000L (SystemClock.elapsedRealtime() после перезагрузки)
            viewModel.onActivityResume(1000L)
            testScheduler.advanceUntilIdle()

            // Проверяем, что обновление было вызвано
            org.mockito.kotlin
                .verify(usageStateRepository)
                .refresh()
        }

    @Test
    fun `onActivityResume does NOT trigger refresh on first call`() =
        runTest(testDispatcher) {
            // lastRefreshAt is 0L by default in SavedStateHandle

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle() // Let initializeApp() finish its refresh
            org.mockito.kotlin.clearInvocations(usageStateRepository)

            // first call to onActivityResume
            viewModel.onActivityResume(1000L)
            testScheduler.advanceUntilIdle()

            // Verify that NO data refresh was triggered
            org.mockito.kotlin
                .verify(usageStateRepository, org.mockito.kotlin.never())
                .refresh()
        }
}
