package com.example.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.feature.main.controller.MainBannerController
import com.example.minutewatcher.feature.main.controller.MainDialogController
import com.example.minutewatcher.feature.main.controller.MainSettingsController
import com.example.minutewatcher.feature.main.controller.MainSimDataController
import com.example.minutewatcher.feature.main.controller.MainSimRefreshController
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
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

        val initialSnapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 0L,
                simSnapshots = emptyList(),
                capabilities = emptyList(),
            )
        whenever(usageStateRepository.observeSnapshot()).thenReturn(MutableStateFlow(initialSnapshot))

        whenever(
            appSettingsRepository.observeAppSettings(),
        ).thenReturn(
            MutableStateFlow(
                AppSettings(
                    onboardingCompleted = true,
                ),
            ),
        )
        whenever(
            appSettingsRepository.getAppSettings(),
        ).thenReturn(
            flowOf(
                AppSettings(
                    onboardingCompleted = true,
                ),
            ),
        )
        whenever(settingsUseCase.appTheme).thenReturn(MutableStateFlow(AppTheme.FOLLOW_SYSTEM))
        whenever(settingsUseCase.appLanguage).thenReturn(MutableStateFlow(null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(scope: CoroutineScope): MainViewModel {
        val dialogController = MainDialogController(savedStateHandle)
        val bannerController =
            MainBannerController(
                trackingStatusRepository = trackingStatusRepository,
                permissionRepository = permissionRepository,
                appSettingsRepository = appSettingsRepository,
                scope = scope,
            )
        val refreshController =
            MainSimRefreshController(
                usageUseCase = usageUseCase,
                usageStateRepository = usageStateRepository,
                ioDispatcher = testDispatcher,
            )
        val settingsController =
            MainSettingsController(
                appSettingsRepository = appSettingsRepository,
                settingsUseCase = settingsUseCase,
                scope = scope,
            )
        val simDataController =
            MainSimDataController(
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
            val settingsFlow =
                MutableStateFlow(
                    AppSettings(
                        phonePermissionRequested = false,
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
                    phonePermissionRequested = true,
                    onboardingCompleted = true,
                )
            testScheduler.advanceUntilIdle()

            bannerState = viewModel.trackingBannerState.value
            assertTrue(bannerState.phonePermissionState == PermissionUiState.DENIED)
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

            verify(settingsUseCase).setTheme(theme)
        }

    @Test
    fun `onActivityResume triggers refresh on reboot (negative elapsed)`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle() // Даем initializeApp() завершиться
            clearInvocations(usageStateRepository)

            // ВАЖНО: Устанавливаем симуляцию состояния "до перезагрузки" ПОСЛЕ initializeApp()
            savedStateHandle[AutoResetScheduler.KEY_LAST_AUTO_RESET_CHECK] = 500_000L

            // nowElapsed = 1000L (SystemClock.elapsedRealtime() после перезагрузки)
            viewModel.onActivityResume(1000L)
            testScheduler.advanceUntilIdle()

            // Проверяем, что обновление было вызвано
            verify(usageStateRepository).refresh()
        }

    @Test
    fun `onActivityResume does NOT trigger refresh on first call`() =
        runTest(testDispatcher) {
            // lastRefreshAt is 0L by default in SavedStateHandle

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle() // Let initializeApp() finish its refresh
            clearInvocations(usageStateRepository)

            // first call to onActivityResume
            viewModel.onActivityResume(1000L)
            testScheduler.advanceUntilIdle()

            // Verify that NO data refresh was triggered
            verify(usageStateRepository, never()).refresh()
        }

    @Test
    fun `refreshSimCardsNow calls onComplete even on failure`() =
        runTest(testDispatcher) {
            whenever(usageUseCase.refreshOperatorInfo()).thenThrow(RuntimeException("Fail"))
            val viewModel = createViewModel(backgroundScope)

            var completed = false
            viewModel.refreshSimCardsNow { completed = true }
            testScheduler.advanceUntilIdle()

            assertTrue("onComplete should be called even on failure", completed)
        }

    @Test
    fun `refreshAndNotify does not update lastRefreshAt on Timeout`() =
        runTest(testDispatcher) {
            val initialSnapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            whenever(usageUseCase.refreshOperatorInfo()).thenAnswer { }
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Timeout(initialSnapshot))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            // Reset after initializeApp
            savedStateHandle[AutoResetScheduler.KEY_LAST_AUTO_RESET_CHECK] = 0L

            viewModel.refreshSimCardsNow()
            testScheduler.advanceUntilIdle()

            assertEquals(0L, savedStateHandle.get<Long>(AutoResetScheduler.KEY_LAST_AUTO_RESET_CHECK))
        }

    @Test
    fun `onActivityResume does not trigger refresh after recent manual pull-to-refresh`() =
        runTest(testDispatcher) {
            val initialSnapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 1000L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(initialSnapshot))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            // Manual refresh sets lastRefreshAt and lastSnapshotHash
            viewModel.refreshSimCardsNow()
            testScheduler.advanceUntilIdle()

            // Verification: lastRefreshAt is set to something non-zero (mock SystemClock.elapsedRealtime if needed,
            // but Robolectric handles it).
            val lastRefresh = savedStateHandle.get<Long>(AutoResetScheduler.KEY_LAST_AUTO_RESET_CHECK) ?: 0L
            assertTrue("lastRefreshAt should be set after pull-to-refresh", lastRefresh > 0)

            clearInvocations(usageStateRepository)

            // Activity resume within threshold (20s)
            val now = lastRefresh + 5000L
            viewModel.onActivityResume(now)
            testScheduler.advanceUntilIdle()

            // Should NOT trigger another refresh
            verify(usageStateRepository, never()).refresh()
        }

    @Test
    fun `refreshAndNotify handles concurrent calls consistently`() =
        runTest(testDispatcher) {
            val snapshot1 =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 1L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            val snapshot2 =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 2L,
                    simSnapshots =
                        listOf(
                            com.example.minutewatcher.core.model.SimSnapshot(
                                slot = 0,
                                identity = "sim_0",
                                operator = "Test",
                                isActive = true,
                                entities = emptyMap(),
                                autoResetDay = 1,
                            ),
                        ),
                    capabilities = emptyList(),
                )

            // initializeApp returns snapshot1
            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot1))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            // Reset after initializeApp
            clearInvocations(dataChangeNotifier)

            // Setup next refresh to return snapshot2
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot2))

            // Launch concurrent refreshes
            launch { viewModel.refreshSimCardsNow() }
            launch { viewModel.refreshSimCardsNow() }

            testScheduler.advanceUntilIdle()

            // Even if multiple calls started, only one should have resulted in notifyDataChanged(true, false)
            // due to the hash change check being atomic with the update.
            // Note: the controller mutex handles the sequential execution of refreshSimCardsInternal,
            // but the hash check in ViewModel is what we're testing atomicity for.
            verify(dataChangeNotifier, times(1)).notifyDataChanged(eq(true), eq(false))
        }

    @Test
    fun `autoResetScheduler uses monotonic time and handles reboot`() =
        runTest(testDispatcher) {
            // 1. Initial run
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(Unit)
            createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            // Verify first check happened
            verify(autoResetUseCase).performAutoReset(eq(false))
            clearInvocations(autoResetUseCase)

            // 2. Simulate "rewinding" wall clock time (System.currentTimeMillis)
            // In the new implementation, this should NOT trigger a check because we use elapsedRealtime.
            // (Note: Robolectric shadows both, but they move forward together unless we jump).

            // Advance time by 30 mins (less than 1h interval)
            testScheduler.advanceTimeBy(30 * 60 * 1000L)

            // Re-simulate initializeApp or just call checkOnStart via a new VM instance with same SavedState
            createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            // Should NOT have called auto-reset again
            verify(autoResetUseCase, never()).performAutoReset(any())

            // 3. Simulate Reboot (elapsedRealtime jumps back)
            // Set KEY_LAST_AUTO_RESET_CHECK to a "future" value relative to current 0
            savedStateHandle["last_auto_reset_check"] = 10_000_000L
            // Current elapsedRealtime is 30 mins (~1.8M), so 10M is in the "future" (or from previous session)

            createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            // Should trigger check because elapsed < 0
            verify(autoResetUseCase).performAutoReset(eq(false))
        }
}
