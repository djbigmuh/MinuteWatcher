package com.example.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.android.testing.SingletonResetRule
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.domain.WidgetUpdateCoordinator
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.feature.main.controller.AutoResetScheduler
import com.example.minutewatcher.feature.main.controller.MainBannerController
import com.example.minutewatcher.feature.main.controller.MainDialogController
import com.example.minutewatcher.feature.main.controller.MainInitializationController
import com.example.minutewatcher.feature.main.controller.MainResumeController
import com.example.minutewatcher.feature.main.controller.MainSettingsController
import com.example.minutewatcher.feature.main.controller.MainSimDataController
import com.example.minutewatcher.feature.main.controller.MainSimRefreshController
import com.example.minutewatcher.feature.main.controller.MainViewModelControllers
import com.example.minutewatcher.feature.main.controller.TrackingRecoveryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
abstract class MainViewModelTestFixtures {
    /**
     * MainViewModel and its controllers (BannerController, etc) involve complex StateFlow
     * combinations with WhileSubscribed sharing strategy and deep nesting of reactive flows.
     * In a Robolectric environment, managing deterministic advancement of StandardTestDispatcher
     * through these layers while ensuring WhileSubscribed triggers correctly at each level
     * proves exceptionally fragile. UnconfinedTestDispatcher is used here to ensure immediate
     * propagation of state changes through the complex reactive chain.
     */
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(kotlinx.coroutines.test.UnconfinedTestDispatcher())

    @get:Rule
    val singletonResetRule = SingletonResetRule()

    protected val testDispatcher: TestDispatcher get() = mainDispatcherRule.testDispatcher
    protected val testScope by lazy { TestScope(testDispatcher) }

    protected val simIdentityRepository: SimIdentityRepository = mock()
    protected val simLimitsRepository: SimLimitsRepository = mock()
    protected val appSettingsRepository: AppSettingsRepository = mock()
    protected val migrationFlagsRepository: MigrationFlagsRepository = mock()
    protected val usageStateRepository: UsageStateRepository = mock()
    protected val trackingStatusRepository: TrackingStatusRepository = mock()
    protected val autoResetUseCase: AutoResetUseCase = mock()
    protected val usageUseCase: UsageUseCase = mock()
    protected val settingsUseCase: SettingsUseCase = mock()
    protected val dataChangeNotifier: DataChangeNotifier = mock()
    protected val updateCoordinator: WidgetUpdateCoordinator = mock()
    protected val dataRefreshNotifier: com.example.minutewatcher.core.domain.DataRefreshNotifier = mock()
    protected val permissionRepository: PermissionRepository = mock()
    protected val seedRepository: SimIdentitySeedRepository = mock()
    protected val trackingRecoveryController: TrackingRecoveryController = mock()
    protected val timeProvider = FixedTimeProvider(currentTime = 1000000L, currentRealtime = 1000000L)
    protected val savedStateHandle = SavedStateHandle()

    protected val phonePermissionFlow = MutableStateFlow(false)
    protected val usageStatsPermissionFlow = MutableStateFlow(false)
    protected val notificationPermissionFlow = MutableStateFlow(false)
    protected val refreshFlow =
        MutableSharedFlow<com.example.minutewatcher.core.domain.DataRefreshNotifier.RefreshEvent>(replay = 1)

    @Before
    open fun setup() {
        initTestLogger()
        phonePermissionFlow.value = false
        usageStatsPermissionFlow.value = false
        notificationPermissionFlow.value = false

        whenever(dataRefreshNotifier.refreshFlow).thenReturn(refreshFlow)
        whenever(permissionRepository.phoneStatePermissionGranted).thenReturn(phonePermissionFlow)
        whenever(permissionRepository.usageStatsPermissionGranted).thenReturn(usageStatsPermissionFlow)
        whenever(permissionRepository.notificationPermissionGranted).thenReturn(notificationPermissionFlow)
        whenever(seedRepository.isEphemeralSeedActive).thenReturn(MutableStateFlow(false))
        whenever(migrationFlagsRepository.isInitialSetupCompleted()).thenReturn(kotlinx.coroutines.flow.flowOf(true))

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

        // Default successful refresh for initializeApp
        kotlinx.coroutines.runBlocking {
            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(initialSnapshot))
        }
    }

    @After
    open fun tearDown() {
        // MainDispatcherRule handles resetMain()
    }

    protected fun createViewModel(scope: CoroutineScope): MainViewModel {
        val dialogController = MainDialogController(savedStateHandle)
        val autoResetScheduler = AutoResetScheduler(savedStateHandle, autoResetUseCase, timeProvider)
        val refreshController =
            MainSimRefreshController(
                usageUseCase = usageUseCase,
                usageStateRepository = usageStateRepository,
                ioDispatcher = testDispatcher,
            )
        val initController =
            MainInitializationController(
                appSettingsRepository = appSettingsRepository,
                refreshController = refreshController,
                dialogController = dialogController,
                autoResetScheduler = autoResetScheduler,
                timeProvider = timeProvider,
                ioDispatcher = testDispatcher,
            )

        val controllers =
            createControllers(
                scope = scope,
                refreshController = refreshController,
            )

        return MainViewModel(
            controllers = controllers,
            dialogController = dialogController,
            initController = initController,
            timeProvider = timeProvider,
            savedStateHandle = savedStateHandle,
        )
    }

    private fun createControllers(
        scope: CoroutineScope,
        refreshController: MainSimRefreshController,
    ): MainViewModelControllers {
        val bannerController =
            MainBannerController(
                trackingStatusRepository = { trackingStatusRepository },
                permissionRepository = permissionRepository,
                appSettingsRepository = appSettingsRepository,
                seedRepository = seedRepository,
                scope = scope,
            )
        val settingsController =
            MainSettingsController(
                appSettingsRepository = appSettingsRepository,
                migrationFlagsRepository = migrationFlagsRepository,
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
            dataRefreshNotifier = dataRefreshNotifier,
            widgetObservationRecovery = { updateCoordinator },
        )
    }
}
