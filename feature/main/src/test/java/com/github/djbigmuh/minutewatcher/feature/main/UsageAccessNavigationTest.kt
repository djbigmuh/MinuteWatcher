package com.github.djbigmuh.minutewatcher.feature.main

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.AutoResetUseCase
import com.github.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import com.github.djbigmuh.minutewatcher.core.domain.PermissionRepository
import com.github.djbigmuh.minutewatcher.core.domain.SettingsUseCase
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import com.github.djbigmuh.minutewatcher.core.domain.TrackingStatusRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageUseCase
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdateCoordinator
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.AppTheme
import com.github.djbigmuh.minutewatcher.feature.main.controller.MainBannerController
import com.github.djbigmuh.minutewatcher.feature.main.controller.MainDialogController
import com.github.djbigmuh.minutewatcher.feature.main.controller.MainResumeController
import com.github.djbigmuh.minutewatcher.feature.main.controller.MainSettingsController
import com.github.djbigmuh.minutewatcher.feature.main.controller.MainSimDataController
import com.github.djbigmuh.minutewatcher.feature.main.controller.MainSimRefreshController
import com.github.djbigmuh.minutewatcher.feature.main.controller.MainViewModelControllers
import com.github.djbigmuh.minutewatcher.feature.main.controller.TrackingRecoveryController
import com.github.djbigmuh.minutewatcher.feature.main.ui.components.UsageAccessDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
// UI-infra SDK pin: Espresso InputManager reflection fails on SDK > 35 in Robolectric
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class UsageAccessNavigationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeTestRule = createComposeRule()

    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val trackingStatusRepository: TrackingStatusRepository = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()
    private val migrationFlagsRepository: MigrationFlagsRepository = mock()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val settingsUseCase: SettingsUseCase = mock()
    private val dataRefreshNotifier: com.github.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier = mock()
    private val updateCoordinator: WidgetUpdateCoordinator = mock()
    private val permissionRepository: PermissionRepository = mock()
    private val trackingRecoveryController: TrackingRecoveryController = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L, currentRealtime = 1000000L)
    private val savedStateHandle = SavedStateHandle()

    private val testDispatcher get() = mainDispatcherRule.testDispatcher

    private val phonePermissionFlow = MutableStateFlow(true)
    private val usageStatsPermissionFlow = MutableStateFlow(false)
    private val notificationPermissionFlow = MutableStateFlow(true)
    private val refreshFlow =
        MutableSharedFlow<com.github.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier.RefreshEvent>(replay = 1)

    @Before
    fun setup() {
        refreshFlow.tryEmit(
            com.github.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
                .RefreshEvent(),
        )
        whenever(dataRefreshNotifier.refreshFlow).thenReturn(refreshFlow)

        whenever(permissionRepository.phoneStatePermissionGranted).thenReturn(phonePermissionFlow)
        whenever(permissionRepository.usageStatsPermissionGranted).thenReturn(usageStatsPermissionFlow)
        whenever(permissionRepository.notificationPermissionGranted).thenReturn(notificationPermissionFlow)
        whenever(seedRepository.isEphemeralSeedActive).thenReturn(MutableStateFlow(false))
        whenever(migrationFlagsRepository.isInitialSetupCompleted()).thenReturn(flowOf(true))

        whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(MutableStateFlow(emptyMap()))
        whenever(seedRepository.isEphemeralSeedActive).thenReturn(MutableStateFlow(false))
        whenever(
            usageStateRepository.observeSimCardUiModels(),
        ).thenReturn(MutableStateFlow(listOf(createMockSimCard())))
        whenever(
            usageStateRepository.observeSnapshot(),
        ).thenReturn(MutableStateFlow(mock<com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot>()))
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings()))
        whenever(settingsUseCase.appTheme).thenReturn(MutableStateFlow(AppTheme.FOLLOW_SYSTEM))
        whenever(settingsUseCase.appLanguage).thenReturn(MutableStateFlow(null))
    }

    private suspend fun TestScope.actAndAdvance(block: suspend () -> Unit) {
        block()
        advanceUntilIdle()
    }

    private fun createViewModel(scope: CoroutineScope): MainViewModel {
        val controllers = createControllers(scope)
        val initController = createInitController(controllers.refresh)
        val dialogController = MainDialogController(savedStateHandle)
        val appInfoProvider: com.github.djbigmuh.minutewatcher.core.domain.AppInfoProvider = mock()
        whenever(appInfoProvider.versionName).thenReturn("1.0.0")
        whenever(appInfoProvider.versionCode).thenReturn(1)

        return MainViewModel(
            controllers = controllers,
            dialogController = dialogController,
            initController = initController,
            timeProvider = timeProvider,
            appInfoProvider = appInfoProvider,
            savedStateHandle = savedStateHandle,
        )
    }

    private fun createControllers(scope: CoroutineScope): MainViewModelControllers {
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
                migrationFlagsRepository = migrationFlagsRepository,
                settingsUseCase = settingsUseCase,
                scope = scope,
                ioDispatcher = testDispatcher,
            )
        val simDataController =
            MainSimDataController(
                usageStateRepository = usageStateRepository,
                trackingRecoveryController = trackingRecoveryController,
                simIdentityRepository = simIdentityRepository,
                simLimitsRepository = simLimitsRepository,
                usageUseCase = usageUseCase,
                dataChangeNotifier = mock(),
                trackingStatusRepository = mock(),
                ioDispatcher = testDispatcher,
                scope = scope,
            )
        val resumeController =
            MainResumeController(
                timeProvider = timeProvider,
                savedStateHandle = savedStateHandle,
            )

        return MainViewModelControllers(
            banner = bannerController,
            refresh = refreshController,
            settings = settingsController,
            simData = simDataController,
            resume = resumeController,
            widgetObservationRecovery = { updateCoordinator },
        )
    }

    private fun createInitController(refreshController: MainSimRefreshController) =
        com.github.djbigmuh.minutewatcher.feature.main.controller.MainInitializationController(
            refreshController = refreshController,
            autoResetScheduler =
                com.github.djbigmuh.minutewatcher.feature.main.controller.AutoResetScheduler(
                    mock(),
                    autoResetUseCase,
                    timeProvider,
                ),
            timeProvider = timeProvider,
            ioDispatcher = testDispatcher,
        )

    private fun createMockSimCard() =
        com.github.djbigmuh.minutewatcher.core.model.SimCardUiModel(
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
            trafficRemainingGb = null,
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
            com.github.djbigmuh.minutewatcher.feature.main.ui.components.TrackingBanners(
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
                        onRetryWidgetObservation = {},
                        onRetrySimDiscovery = {},
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
            actAndAdvance {} // initial advance

            actAndAdvance { viewModel.setUsageAccessDialog(true) }
            assertTrue(viewModel.showUsageAccessDialog.value)

            actAndAdvance { viewModel.setUsageAccessDialog(true) } // Call again
            assertTrue(viewModel.showUsageAccessDialog.value)

            actAndAdvance { viewModel.setUsageAccessDialog(false) }
            assertFalse(viewModel.showUsageAccessDialog.value)
        }
}
