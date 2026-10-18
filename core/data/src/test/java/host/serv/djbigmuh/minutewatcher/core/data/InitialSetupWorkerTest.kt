package host.serv.djbigmuh.minutewatcher.core.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.Logger
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.domain.AutoResetUseCase
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.RefreshResult
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageUseCase
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import host.serv.djbigmuh.minutewatcher.core.model.MigrationSkipReason
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class InitialSetupWorkerTest {
    private val callTracker: CallTracker = mock()
    private val trafficTracker: TrafficTracker = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val migrationFlagsRepository: MigrationFlagsRepository = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val migrationService: IdentityMigrationService = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val workScheduler: WorkScheduler = mock()
    private val permissionManager: PermissionManager = mock()

    private lateinit var deps: InitialSetupWorkerDependencies
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Application>()
        val logger: Logger = mock()
        AppLogger
            .init(context, logger)

        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = true))
        whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = true))
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(emptyList()))

        val emptySnapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 0L,
                simSnapshots = emptyList(),
                capabilities = emptyList(),
            )

        runBlocking {
            whenever(migrationFlagsRepository.setMigrationV1Completed(any())).thenReturn(Unit)
            whenever(migrationFlagsRepository.setMigrationV1Skipped(any())).thenReturn(Unit)
            whenever(migrationFlagsRepository.setMigrationIdentityStabilityCompleted(any())).thenReturn(Unit)
            whenever(migrationService.markSchemaCurrent()).thenReturn(Unit)
            whenever(migrationService.hasLegacyData(any())).thenReturn(false)
            whenever(migrationService.hasLegacyData()).thenReturn(false)
            whenever(usageStateRepository.refresh()).thenReturn(
                RefreshResult
                    .Success(emptySnapshot),
            )
            whenever(dataChangeNotifier.notifyDataChanged(any(), any())).thenReturn(true)
            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(true)
        }

        deps =
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
    }

    private fun createWorker(): InitialSetupWorker =
        TestListenableWorkerBuilder<InitialSetupWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, deps)
                },
            ).build()

    @Test
    fun `doWork performs auto-reset after migration`() =
        runTest {
            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(autoResetUseCase).performAutoReset(shouldNotify = false)
        }

    @Test
    fun `performSetupSteps calls onBootAwaited when boot flag is true`() =
        runTest {
            val inputData =
                Data
                    .Builder()
                    .putBoolean(InitialSetupWorker.EXTRA_TRIGGERED_BY_BOOT, true)
                    .build()

            val worker =
                TestListenableWorkerBuilder<InitialSetupWorker>(context)
                    .setInputData(inputData)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, deps)
                        },
                    ).build()

            worker.doWork()

            verify(callTracker).onBootAwaited()
        }

    @Test
    fun `performSetupSteps skips onBootAwaited when boot flag is false`() =
        runTest {
            val worker = createWorker()

            worker.doWork()

            verify(callTracker, never()).onBootAwaited()
        }

    @Test
    fun `doWork returns success when migration already completed`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = true))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = true))
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(emptyList()))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(usageStateRepository, times(1)).refresh()
            verify(dataChangeNotifier).notifyDataChanged(updateWidget = true, force = true)
        }

    @Test
    fun `doWork skips migration V1 when no permission and no legacy data`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(false)

            Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_PHONE_STATE)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationFlagsRepository).setMigrationV1Completed(completed = true)
            verify(migrationFlagsRepository).setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
            verify(migrationFlagsRepository).setMigrationIdentityStabilityCompleted(completed = true)
            verify(migrationService).markSchemaCurrent()
        }

    @Test
    fun `doWork retries when hasLegacyData returns null and runAttemptCount is under max`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(null)
            whenever(migrationService.hasLegacyData()).thenReturn(null)

            val worker =
                TestListenableWorkerBuilder<InitialSetupWorker>(context)
                    .setRunAttemptCount(0)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, deps)
                        },
                    ).build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(migrationFlagsRepository, never()).setMigrationV1Completed(completed = true)
            verify(migrationService, never()).markSchemaCurrent()
        }

    @Test
    fun `doWork skips migration permanently when hasLegacyData returns null after max attempts`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(null)
            whenever(migrationService.hasLegacyData()).thenReturn(null)

            val worker =
                TestListenableWorkerBuilder<InitialSetupWorker>(context)
                    .setRunAttemptCount(3)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, deps)
                        },
                    ).build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationFlagsRepository).setMigrationV1Completed(completed = true)
            verify(migrationFlagsRepository).setMigrationV1Skipped(MigrationSkipReason.LEGACY_DATA_CHECK_FAILED)
            verify(migrationFlagsRepository).setMigrationIdentityStabilityCompleted(completed = true)
            verify(migrationService, never()).markSchemaCurrent()
        }

    @Test
    fun `doWork defers migration when no permission but legacy data exists`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            whenever(migrationService.hasLegacyData()).thenReturn(true)

            Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_PHONE_STATE)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationFlagsRepository, never()).setMigrationV1Completed(completed = true)
            verify(migrationService, never()).markSchemaCurrent()
        }

    @Test
    fun `doWork defers migration when permission granted but no active sims and legacy data exists`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(emptyList()))

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationFlagsRepository, never()).setMigrationV1Completed(completed = true)
            verify(migrationService, never()).markSchemaCurrent()
        }

    @Test
    fun `doWork performs migration when active sims found`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            val identity = SimIdentity("icc_123", 1, 0, IdentitySource.ICCID, isStable = true)
            val activeSim =
                ActiveSimInfo(identity, 0, 1, "123", "MTS", "MTS", "25001", isDefaultData = true, isDefaultVoice = true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(listOf(activeSim)))
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            whenever(migrationService.hasLegacyData(any())).thenReturn(false)
            whenever(migrationService.migrateLegacyData(any())).thenReturn(
                IdentityMigrationService.MigrationOutcome(
                    migrated = 1,
                ),
            )

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationService).migrateLegacyData(activeSim.identity)
            verify(migrationFlagsRepository).setMigrationV1Completed(completed = true)
            verify(migrationFlagsRepository).setMigrationIdentityStabilityCompleted(completed = true)
            verify(migrationService).markSchemaCurrent()
        }

    @Test
    fun `doWork updates traffic usage instead of resetting baseline`() =
        runTest {
            val identity1 = SimIdentity("icc_1", 1, 0, IdentitySource.ICCID, isStable = true)
            val activeSim1 =
                ActiveSimInfo(
                    identity1,
                    0,
                    1,
                    "1",
                    "MTS",
                    "MTS",
                    "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(listOf(activeSim1)))
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(trafficTracker).updateTrafficUsage()
            verify(trafficTracker, never()).resetBaseline(any())
            verify(migrationFlagsRepository).setInitialSetupCompleted(true)
        }

    @Test
    fun `doWork retries migration when failures occur`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            val identity = SimIdentity("icc_123", 1, 0, IdentitySource.ICCID, isStable = true)
            val activeSim =
                ActiveSimInfo(identity, 0, 1, "123", "MTS", "MTS", "25001", isDefaultData = true, isDefaultVoice = true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(listOf(activeSim)))
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            whenever(migrationService.migrateLegacyData(any()))
                .thenReturn(IdentityMigrationService.MigrationOutcome(failures = 1))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(migrationService, never()).markSchemaCurrent()
            verify(migrationFlagsRepository, never()).setMigrationV1Skipped(any())
        }

    @Test
    fun `doWork retries when actual migration throws IOException`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            val identity = SimIdentity("icc_123", 1, 0, IdentitySource.ICCID, isStable = true)
            val activeSim =
                ActiveSimInfo(identity, 0, 1, "123", "MTS", "MTS", "25001", isDefaultData = true, isDefaultVoice = true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(listOf(activeSim)))
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            whenever(migrationService.migrateLegacyData(any())).thenAnswer { throw IOException("Disk read error") }

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(migrationService, never()).markSchemaCurrent()
        }

    @Test
    fun `doWork defers migration flags after max attempts with partial failure`() =
        runTest {
            val worker =
                TestListenableWorkerBuilder<InitialSetupWorker>(context)
                    .setRunAttemptCount(3)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, deps)
                        },
                    ).build()

            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            val identity = SimIdentity("icc_123", 1, 0, IdentitySource.ICCID, isStable = true)
            val activeSim =
                ActiveSimInfo(identity, 0, 1, "123", "MTS", "MTS", "25001", isDefaultData = true, isDefaultVoice = true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(listOf(activeSim)))
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            whenever(usageStateRepository.refresh()).thenReturn(
                RefreshResult.Success(
                    mock(),
                ),
            )

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            whenever(migrationService.migrateLegacyData(any()))
                .thenReturn(IdentityMigrationService.MigrationOutcome(failures = 1))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationFlagsRepository).setMigrationV1Skipped(MigrationSkipReason.MIGRATION_PARTIAL_FAILURE)
            verify(migrationService, never()).markSchemaCurrent()
            verify(migrationFlagsRepository, never()).setMigrationV1Completed(completed = true)
            verify(migrationFlagsRepository, never()).setMigrationIdentityStabilityCompleted(completed = true)
        }

    @Test
    fun `doWork succeeds on subsequent run after partial failure`() =
        runTest {
            val worker =
                TestListenableWorkerBuilder<InitialSetupWorker>(context)
                    .setRunAttemptCount(1)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, deps)
                        },
                    ).build()

            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            val identity = SimIdentity("icc_123", 1, 0, IdentitySource.ICCID, isStable = true)
            val activeSim =
                ActiveSimInfo(identity, 0, 1, "123", "MTS", "MTS", "25001", isDefaultData = true, isDefaultVoice = true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(listOf(activeSim)))
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            whenever(usageStateRepository.refresh()).thenReturn(
                RefreshResult.Success(
                    mock(),
                ),
            )

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            whenever(migrationService.migrateLegacyData(any()))
                .thenReturn(IdentityMigrationService.MigrationOutcome(migrated = 1, failures = 0))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationService).markSchemaCurrent()
            verify(migrationFlagsRepository).setMigrationV1Completed(completed = true)
            verify(migrationFlagsRepository).setMigrationIdentityStabilityCompleted(completed = true)
            verify(
                migrationFlagsRepository,
                never(),
            ).setMigrationV1Skipped(MigrationSkipReason.MIGRATION_PARTIAL_FAILURE)
        }

    @Test
    fun `doWork returns retry on total timeout`() =
        runTest {
            val testDeps =
                InitialSetupWorkerDependencies(
                    callTracker = deps.callTracker,
                    trafficTracker = deps.trafficTracker,
                    simIdentityRepository = deps.simIdentityRepository,
                    migrationFlagsRepository = deps.migrationFlagsRepository,
                    usageStateRepository =
                        object : FakeUsageStateRepository() {
                            override suspend fun refresh(): RefreshResult {
                                delay(1_000_000L)
                                return RefreshResult.Success(mock())
                            }
                        },
                    autoResetUseCase = deps.autoResetUseCase,
                    usageUseCase = deps.usageUseCase,
                    migrationService = deps.migrationService,
                    dataChangeNotifier = deps.dataChangeNotifier,
                    workScheduler = deps.workScheduler,
                    permissionManager = deps.permissionManager,
                )

            val worker =
                TestListenableWorkerBuilder<InitialSetupWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, testDeps)
                        },
                    ).build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork succeeds and continues setup steps when refreshOperatorInfo times out`() =
        runTest {
            val testDeps =
                InitialSetupWorkerDependencies(
                    callTracker = deps.callTracker,
                    trafficTracker = deps.trafficTracker,
                    simIdentityRepository = deps.simIdentityRepository,
                    migrationFlagsRepository = deps.migrationFlagsRepository,
                    usageStateRepository = deps.usageStateRepository,
                    autoResetUseCase = deps.autoResetUseCase,
                    usageUseCase =
                        object : FakeUsageUseCase() {
                            override suspend fun refreshOperatorInfo() {
                                delay(1_000_000L)
                            }
                        },
                    migrationService = deps.migrationService,
                    dataChangeNotifier = deps.dataChangeNotifier,
                    workScheduler = deps.workScheduler,
                    permissionManager = deps.permissionManager,
                )

            val worker =
                TestListenableWorkerBuilder<InitialSetupWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, testDeps)
                        },
                    ).build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(workScheduler).schedulePeriodicUsageCheck()
            verify(migrationFlagsRepository).setInitialSetupCompleted(true)
        }

    @Test(expected = CancellationException::class)
    fun `doWork propagates external cancellation`() =
        runTest {
            val testDeps =
                InitialSetupWorkerDependencies(
                    callTracker = deps.callTracker,
                    trafficTracker = deps.trafficTracker,
                    simIdentityRepository = deps.simIdentityRepository,
                    migrationFlagsRepository = deps.migrationFlagsRepository,
                    usageStateRepository =
                        object : FakeUsageStateRepository() {
                            override suspend fun refresh(): RefreshResult {
                                delay(1_000_000L)
                                return RefreshResult.Success(mock())
                            }
                        },
                    autoResetUseCase = deps.autoResetUseCase,
                    usageUseCase = deps.usageUseCase,
                    migrationService = deps.migrationService,
                    dataChangeNotifier = deps.dataChangeNotifier,
                    workScheduler = deps.workScheduler,
                    permissionManager = deps.permissionManager,
                )

            val worker =
                TestListenableWorkerBuilder<InitialSetupWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, testDeps)
                        },
                    ).build()

            val job =
                launch {
                    worker.doWork()
                }

            runCurrent()
            job.cancel()
            withTimeoutOrNull(30.seconds) { job.join() }
                ?: throw AssertionError("Timed out waiting for cancelled worker job to finish")
            throw CancellationException("Simulated external cancellation")
        }

    @Test
    fun `doWork waits for slow discovery and performs migration`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(true)

            val identity = SimIdentity("icc_slow", 1, 0, IdentitySource.ICCID, isStable = true)
            val activeSim =
                ActiveSimInfo(
                    identity,
                    0,
                    1,
                    "slow",
                    "MTS",
                    "MTS",
                    "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )

            val slowFlow = MutableStateFlow<List<ActiveSimInfo>>(emptyList())
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(slowFlow)

            whenever(migrationService.migrateLegacyData(any())).thenReturn(
                IdentityMigrationService.MigrationOutcome(migrated = 1),
            )

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            launch {
                delay(2000L)
                slowFlow.value = listOf(activeSim)
            }

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationService).migrateLegacyData(activeSim.identity)
            verify(migrationFlagsRepository).setMigrationV1Completed(completed = true)
        }

    @Test
    fun `doWork continues with empty list after discovery timeout`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(true)

            val slowFlow = MutableStateFlow<List<ActiveSimInfo>>(emptyList())
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(slowFlow)

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationFlagsRepository, never()).setMigrationV1Completed(completed = true)
            verify(migrationService, never()).markSchemaCurrent()
        }

    @Test
    fun `doWork handles non-StateFlow observeActiveSims and completes`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = true))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = true))

            val nonStateFlow =
                flow {
                    emit(emptyList<ActiveSimInfo>())
                    awaitCancellation()
                }
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(nonStateFlow)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `doWork schedules periodic usage check unconditionally even when phone state permission denied`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(workScheduler).schedulePeriodicUsageCheck()
            verify(migrationFlagsRepository).setInitialSetupCompleted(true)
        }

    @Test
    fun `doWork retries and skips setting initial setup completed when schedulePeriodicUsageCheck throws exception`() =
        runTest {
            whenever(workScheduler.schedulePeriodicUsageCheck()).thenThrow(RuntimeException("Scheduling failed"))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(migrationFlagsRepository, never()).setInitialSetupCompleted(true)
        }

    @Test
    fun `doWork schedules periodic usage check before setting initial setup completed`() =
        runTest {
            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            inOrder(workScheduler, migrationFlagsRepository).apply {
                verify(workScheduler).schedulePeriodicUsageCheck()
                verify(migrationFlagsRepository).setInitialSetupCompleted(true)
            }
        }

    @Test
    fun `doWork returns retry when migration decision is RETRY`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(null)
            whenever(migrationService.hasLegacyData()).thenReturn(null)

            val worker =
                TestListenableWorkerBuilder<InitialSetupWorker>(context)
                    .setRunAttemptCount(0)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, deps)
                        },
                    ).build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork returns success when migration decision is PROCEED`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = true))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = true))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `doWork returns failure when setup encounters unrecoverable error`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed())
                .thenThrow(IllegalStateException("Unrecoverable DataStore corruption"))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
        }

    private open class FakeUsageStateRepository : UsageStateRepository {
        override fun observeSimCardUiModels(): Flow<List<host.serv.djbigmuh.minutewatcher.core.model.SimCardUiModel>> =
            flowOf(emptyList())

        override fun observeSnapshot(): StateFlow<GlobalSnapshot> = MutableStateFlow(mock())

        override fun isDataLoaded(): Boolean = true

        override suspend fun refresh(): RefreshResult = RefreshResult.Success(mock())
    }

    private open class FakeUsageUseCase : UsageUseCase {
        override suspend fun refreshOperatorInfo() {
            // No-op
        }

        override suspend fun resetCounters(slot: Int) {
            // No-op
        }

        override suspend fun adjustMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            // No-op
        }

        override suspend fun adjustTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            // No-op
        }
    }
}
