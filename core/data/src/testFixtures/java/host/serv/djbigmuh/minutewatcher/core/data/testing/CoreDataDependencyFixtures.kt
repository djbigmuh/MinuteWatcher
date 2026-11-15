package host.serv.djbigmuh.minutewatcher.core.data.testing

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.data.IdentityMigrationService
import host.serv.djbigmuh.minutewatcher.core.data.InitialSetupWorkerDependencies
import host.serv.djbigmuh.minutewatcher.core.data.MockSimProvider
import host.serv.djbigmuh.minutewatcher.core.data.SimDiscoveryDependencies
import host.serv.djbigmuh.minutewatcher.core.data.SimIdentityRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.data.SimIdentityRepositoryTestHelper
import host.serv.djbigmuh.minutewatcher.core.data.TrafficBaselineManager
import host.serv.djbigmuh.minutewatcher.core.data.TrafficDeltaCalculator
import host.serv.djbigmuh.minutewatcher.core.data.TrafficPermissionChecker
import host.serv.djbigmuh.minutewatcher.core.data.TrafficSimChangeHandler
import host.serv.djbigmuh.minutewatcher.core.data.TrafficTargetResolver
import host.serv.djbigmuh.minutewatcher.core.data.TrafficTrackerDependencies
import host.serv.djbigmuh.minutewatcher.core.data.UsageRepositoryDependencies
import host.serv.djbigmuh.minutewatcher.core.data.UsageWorkerDependencies
import host.serv.djbigmuh.minutewatcher.core.data.semantic.SemanticCapabilities
import host.serv.djbigmuh.minutewatcher.core.data.semantic.SemanticVersion
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.AutoResetUseCase
import host.serv.djbigmuh.minutewatcher.core.domain.CallTracker
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimNotificationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@Suppress("LongParameterList")
fun createSimDiscoveryDependencies(
    context: Context = mock(),
    subscriptionManager: SubscriptionManager? = mock(),
    permissionManager: PermissionManager = mock(),
    permissionStateNotifier: PermissionStateNotifier = mock(),
): SimDiscoveryDependencies =
    SimDiscoveryDependencies(
        context = context,
        subscriptionManager = subscriptionManager,
        permissionManager = permissionManager,
        permissionStateNotifier = permissionStateNotifier,
    )

@Suppress("LongParameterList")
fun createUsageRepositoryDependencies(
    simIdentityRepository: SimIdentityRepository = FakeSimIdentityRepository(),
    simUsageRepository: SimUsageRepository = FakeSimUsageRepository(),
    semanticCapabilities: SemanticCapabilities = SemanticCapabilities(enabled = true),
    semanticVersion: SemanticVersion = SemanticVersion(1),
    dataRefreshNotifier: DataRefreshNotifier = FakeDataRefreshNotifier(),
    timeProvider: TimeProvider = createFixedTimeProvider(),
): UsageRepositoryDependencies =
    UsageRepositoryDependencies(
        simIdentityRepository = simIdentityRepository,
        simUsageRepository = simUsageRepository,
        semanticCapabilities = semanticCapabilities,
        semanticVersion = semanticVersion,
        dataRefreshNotifier = dataRefreshNotifier,
        timeProvider = timeProvider,
    )

@Suppress("LongParameterList")
fun createTrafficTrackerDependencies(
    simIdentityRepository: SimIdentityRepository = FakeSimIdentityRepository(),
    simUsageRepository: SimUsageRepository = FakeSimUsageRepository(),
    simUsageResetRepository: SimUsageResetRepository = FakeSimUsageResetRepository(),
    permissionManager: PermissionManager = mock(),
    baselineManager: TrafficBaselineManager = mock(),
    deltaCalculator: TrafficDeltaCalculator = TrafficDeltaCalculator(simUsageResetRepository),
    simChangeHandler: TrafficSimChangeHandler = mock(),
    targetResolver: TrafficTargetResolver = TrafficTargetResolver(permissionManager),
    permissionChecker: TrafficPermissionChecker = TrafficPermissionChecker(permissionManager),
    permissionStateNotifier: PermissionStateNotifier = mock(),
    dataChangeNotifier: DataChangeNotifier = FakeDataChangeNotifier(),
    timeProvider: TimeProvider = createFixedTimeProvider(),
): TrafficTrackerDependencies =
    TrafficTrackerDependencies(
        simIdentityRepository = simIdentityRepository,
        simUsageRepository = simUsageRepository,
        simUsageResetRepository = simUsageResetRepository,
        permissionManager = permissionManager,
        baselineManager = baselineManager,
        deltaCalculator = deltaCalculator,
        simChangeHandler = simChangeHandler,
        targetResolver = targetResolver,
        permissionChecker = permissionChecker,
        permissionStateNotifier = permissionStateNotifier,
        dataChangeNotifier = dataChangeNotifier,
        timeProvider = timeProvider,
    )

@Suppress("LongParameterList")
fun createInitialSetupWorkerDependencies(
    callTracker: CallTracker = mock(),
    trafficTracker: host.serv.djbigmuh.minutewatcher.core.data.TrafficTracker = mock(),
    simIdentityRepository: SimIdentityRepository = FakeSimIdentityRepository(),
    migrationFlagsRepository: MigrationFlagsRepository = FakeMigrationFlagsRepository(),
    usageStateRepository: UsageStateRepository = FakeUsageStateRepository(),
    autoResetUseCase: AutoResetUseCase = mock(),
    usageUseCase: UsageUseCase = mock(),
    migrationService: IdentityMigrationService = mock(),
    dataChangeNotifier: DataChangeNotifier = FakeDataChangeNotifier(),
    workScheduler: host.serv.djbigmuh.minutewatcher.core.data.WorkScheduler = mock(),
    permissionManager: PermissionManager = mock(),
): InitialSetupWorkerDependencies =
    InitialSetupWorkerDependencies(
        callTracker = callTracker,
        trafficTracker = trafficTracker,
        simIdentityRepository = simIdentityRepository,
        migrationFlagsRepository = migrationFlagsRepository,
        usageStateRepository = usageStateRepository,
        autoResetUseCase = autoResetUseCase,
        usageUseCase = usageUseCase,
        migrationService = migrationService,
        dataChangeNotifier = dataChangeNotifier,
        workScheduler = workScheduler,
        permissionManager = permissionManager,
    )

@Suppress("LongParameterList")
fun createUsageWorkerDependencies(
    simNotificationFlagsRepository: SimNotificationFlagsRepository = FakeSimNotificationFlagsRepository(),
    appSettingsRepository: AppSettingsRepository = FakeAppSettingsRepository(),
    autoResetUseCase: AutoResetUseCase = mock(),
    trafficTracker: host.serv.djbigmuh.minutewatcher.core.data.TrafficTracker = mock(),
    notificationHelper: host.serv.djbigmuh.minutewatcher.core.data.NotificationHelper = mock(),
    dataChangeNotifier: DataChangeNotifier = FakeDataChangeNotifier(),
    usageStateRepository: UsageStateRepository = FakeUsageStateRepository(),
    usageUseCase: UsageUseCase = mock(),
    timeProvider: TimeProvider = createFixedTimeProvider(),
): UsageWorkerDependencies =
    UsageWorkerDependencies(
        simNotificationFlagsRepository = simNotificationFlagsRepository,
        appSettingsRepository = appSettingsRepository,
        autoResetUseCase = autoResetUseCase,
        trafficTracker = trafficTracker,
        notificationHelper = notificationHelper,
        dataChangeNotifier = dataChangeNotifier,
        usageStateRepository = usageStateRepository,
        usageUseCase = usageUseCase,
        timeProvider = timeProvider,
    )

@Suppress("LongParameterList")
fun createTestSimIdentityRepository(
    discoveryDeps: SimDiscoveryDependencies = createSimDiscoveryDependencies(),
    dataStore: DataStore<Preferences> = createTestDataStore(),
    seedRepository: SimIdentitySeedRepository = FakeSimIdentitySeedRepository(),
    appSettingsRepository: AppSettingsRepository = FakeAppSettingsRepository(),
    migrationService: IdentityMigrationService = mock {
        on { migrateLegacyData(any()) } doReturn IdentityMigrationService.MigrationOutcome()
    },
    mockSimProvider: MockSimProvider = FakeMockSimProvider(),
    timeProvider: TimeProvider = createFixedTimeProvider(),
    scope: CoroutineScope = TestScope(),
): SimIdentityRepositoryImpl =
    SimIdentityRepositoryTestHelper.createRepository(
        discoveryDeps = discoveryDeps,
        dataStore = dataStore,
        seedRepository = seedRepository,
        appSettingsRepository = appSettingsRepository,
        migrationService = migrationService,
        mockSimProvider = mockSimProvider,
        timeProvider = timeProvider,
        scope = scope,
    )
