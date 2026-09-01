package com.example.minutewatcher.feature.main

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.feature.main.controller.MainBannerController
import com.example.minutewatcher.feature.main.controller.MainDialogController
import com.example.minutewatcher.feature.main.controller.MainSettingsController
import com.example.minutewatcher.feature.main.controller.MainSimDataController
import com.example.minutewatcher.feature.main.controller.MainSimRefreshController
import com.example.minutewatcher.feature.main.controller.MainViewModelControllers
import com.example.minutewatcher.feature.main.controller.TrackingRecoveryController
import com.example.minutewatcher.feature.main.ui.components.UsageAccessDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageAccessNavigationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val trackingStatusRepository: TrackingStatusRepository = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val settingsUseCase: SettingsUseCase = mock()
    private val dataRefreshNotifier: com.example.minutewatcher.core.domain.DataRefreshNotifier = mock()
    private val updateCoordinator: com.example.minutewatcher.core.data.UpdateCoordinator = mock()
    private val permissionRepository: PermissionRepository = mock()
    private val trackingRecoveryController: TrackingRecoveryController = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L, currentRealtime = 1000000L)
    private val savedStateHandle = SavedStateHandle()

    private val testDispatcher = UnconfinedTestDispatcher()

    private val phonePermissionFlow = MutableStateFlow(true)
    private val usageStatsPermissionFlow = MutableStateFlow(false)
    private val notificationPermissionFlow = MutableStateFlow(true)
    private val refreshFlow =
        MutableSharedFlow<com.example.minutewatcher.core.domain.DataRefreshNotifier.RefreshEvent>(replay = 1)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        refreshFlow.tryEmit(
            com.example.minutewatcher.core.domain.DataRefreshNotifier
                .RefreshEvent(),
        )
        whenever(dataRefreshNotifier.refreshFlow).thenReturn(refreshFlow)

        whenever(permissionRepository.phoneStatePermissionGranted).thenReturn(phonePermissionFlow)
        whenever(permissionRepository.usageStatsPermissionGranted).thenReturn(usageStatsPermissionFlow)
        whenever(permissionRepository.notificationPermissionGranted).thenReturn(notificationPermissionFlow)

        whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(MutableStateFlow(emptyMap()))
        whenever(seedRepository.isEphemeralSeedActive).thenReturn(MutableStateFlow(false))
        whenever(
            usageStateRepository.observeSimCardUiModels(),
        ).thenReturn(MutableStateFlow(listOf(createMockSimCard())))
        whenever(
            usageStateRepository.observeSnapshot(),
        ).thenReturn(MutableStateFlow(mock<com.example.minutewatcher.core.model.GlobalSnapshot>()))
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings()))
        whenever(settingsUseCase.appTheme).thenReturn(MutableStateFlow(AppTheme.FOLLOW_SYSTEM))
        whenever(settingsUseCase.appLanguage).thenReturn(MutableStateFlow(null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Suppress("LongMethod")
    private fun createViewModel(scope: CoroutineScope): MainViewModel {
        val dialogController = MainDialogController(savedStateHandle)
        val bannerController =
            MainBannerController(
                trackingStatusRepository = { trackingStatusRepository },
                permissionRepository = permissionRepository,
                appSettingsRepository = appSettingsRepository,
                seedRepository = seedRepository,
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
                dataChangeNotifier = mock<com.example.minutewatcher.core.domain.DataChangeNotifier>(),
                ioDispatcher = testDispatcher,
                scope = scope,
            )

        val autoResetScheduler =
            com.example.minutewatcher.feature.main.controller.AutoResetScheduler(
                savedStateHandle,
                autoResetUseCase,
                timeProvider,
            )
        val initController =
            com.example.minutewatcher.feature.main.controller.MainInitializationController(
                appSettingsRepository = appSettingsRepository,
                refreshController = refreshController,
                dialogController = dialogController,
                autoResetScheduler = autoResetScheduler,
                timeProvider = timeProvider,
                ioDispatcher = testDispatcher,
            )
        val resumeController =
            com.example.minutewatcher.feature.main.controller
                .MainResumeController(timeProvider)

        val controllers =
            MainViewModelControllers(
                banner = bannerController,
                refresh = refreshController,
                settings = settingsController,
                simData = simDataController,
                resume = resumeController,
                dataRefreshNotifier = dataRefreshNotifier,
                widgetObservationRecovery = dagger.Lazy { updateCoordinator },
            )

        return MainViewModel(
            controllers = controllers,
            dialogController = dialogController,
            initController = initController,
            timeProvider = timeProvider,
            savedStateHandle = savedStateHandle,
        )
    }

    private fun createMockSimCard() =
        com.example.minutewatcher.core.model.SimCardUiModel(
            slot = 0,
            identity = "sim1",
            operatorName = "MTS",
            iconRes = 0,
            minutesUsed = 0,
            minutesLimit = 0,
            manualAdjustment = 0,
            minutesRemaining = 0,
            effectiveMinutesLimit = 0,
            isMinutesLimitSet = false,
            trafficUsedMb = 0f,
            trafficLimitMb = 0,
            trafficRemainingGb = 0f,
            effectiveTrafficLimitGb = 0f,
            trafficAdjustmentGb = 0f,
            isTrafficLimitSet = false,
            autoResetEnabled = false,
            autoResetDay = 1,
            rolloverEnabled = false,
        )

    @Test
    fun bannerOpensRationaleDialog() {
        var dialogOpened = false
        composeTestRule.setContent {
            com.example.minutewatcher.feature.main.ui.components.TrackingBanners(
                state = TrackingBannerState(showUsageAccessBanner = true),
                callbacks =
                    TrackingBannerCallbacks(
                        onGrantPhonePermission = {},
                        onShowUsageAccessDialog = { dialogOpened = true },
                        onOpenAppSettings = {},
                        onRetryAppSearch = {},
                        onDismissAppSearch = {},
                        onRetryCalls = {},
                        onRetryShortcuts = {},
                        onRetryTraffic = {},
                        onGrantNotificationPermission = {},
                        onDismissWidgetError = {},
                        onDismissInaccurateCalls = {},
                        onRetryBanners = {},
                        onRetryWidgetObservation = {},
                    ),
            )
        }

        val actionText = "Settings"
        composeTestRule.onNodeWithText(actionText).performClick()
        assertTrue(dialogOpened)
    }

    @Test
    fun dialogSettingsButtonCallsCallback() {
        // Force run
        var settingsOpened = false
        composeTestRule.setContent {
            UsageAccessDialog(
                onDismiss = {},
                onSettings = { settingsOpened = true },
            )
        }

        val actionText = "Settings"
        composeTestRule.onNodeWithText(actionText).performClick()
        assertTrue(settingsOpened)
    }

    @Test
    fun dialogDoesNotOpenRepeatedly() =
        runTest {
            // Test ViewModel state consistency
            val viewModel = createViewModel(backgroundScope)
            viewModel.setUsageAccessDialog(true)
            assertTrue(viewModel.showUsageAccessDialog.value)

            viewModel.setUsageAccessDialog(true) // Call again
            assertTrue(viewModel.showUsageAccessDialog.value)

            viewModel.setUsageAccessDialog(false)
            assertFalse(viewModel.showUsageAccessDialog.value)
        }
}
