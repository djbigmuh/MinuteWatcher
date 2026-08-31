package com.example.minutewatcher.core.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.MigrationSkipReason
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
    private val permissionManager: com.example.minutewatcher.core.common.util.PermissionManager = mock()

    private lateinit var deps: InitialSetupWorkerDependencies
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Application>()
        val logger: com.example.minutewatcher.core.common.util.Logger = mock()
        com.example.minutewatcher.core.common.util.AppLogger
            .init(context, logger)

        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = true))
        whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = true))
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

        val emptySnapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 0L,
                simSnapshots = emptyList(),
                capabilities = emptyList(),
            )

        kotlinx.coroutines.runBlocking {
            whenever(migrationFlagsRepository.setMigrationV1Completed(any())).thenReturn(Unit)
            whenever(migrationFlagsRepository.setMigrationV1Skipped(any())).thenReturn(Unit)
            whenever(migrationFlagsRepository.setMigrationIdentityStabilityCompleted(any())).thenReturn(Unit)
            whenever(migrationService.markSchemaCurrent()).thenReturn(Unit)
            whenever(migrationService.hasLegacyData(any())).thenReturn(false)
            whenever(migrationService.hasLegacyData()).thenReturn(false)
            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult
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
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
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
    fun `doWork returns success when migration already completed`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = true))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = true))
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

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
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

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
            val identity = SimIdentity("icc_123", 1, 0, IdentitySource.ICCID)
            val activeSim =
                ActiveSimInfo(identity, 0, 1, "MTS", "MTS", "25001", isDefaultData = true, isDefaultVoice = true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            // Ensure suspending functions are mocked explicitly
            whenever(migrationService.hasLegacyData(any())).thenReturn(false)
            whenever(migrationService.migrateLegacyData(any())).thenReturn(
                IdentityMigrationService.MigrationOutcome(
                    migrated = 1
                )
            )

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationService).migrateLegacyData(activeSim.identity)
            verify(migrationFlagsRepository).setMigrationV1Completed(completed = true)
            verify(migrationFlagsRepository).setMigrationIdentityStabilityCompleted(completed = true)
            verify(migrationService).markSchemaCurrent()
        }

    @Test
    fun `doWork resets baseline only for default data SIM`() =
        runTest {
            val identity1 = SimIdentity("icc_1", 1, 0, IdentitySource.ICCID)
            val activeSim1 =
                ActiveSimInfo(
                    identity1,
                    0,
                    1,
                    "MTS",
                    "MTS",
                    "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )

            val identity2 = SimIdentity("icc_2", 2, 1, IdentitySource.ICCID)
            val activeSim2 =
                ActiveSimInfo(
                    identity2,
                    1,
                    2,
                    "MegaFon",
                    "MegaFon",
                    "25002",
                    isDefaultData = false,
                    isDefaultVoice = false,
                )

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim1, activeSim2)))
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(trafficTracker).resetBaseline(eq("icc_1"))
            verify(trafficTracker, never()).resetBaseline(eq("icc_2"))
        }

    @Test
    fun `doWork retries migration when failures occur`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            val identity = SimIdentity("icc_123", 1, 0, IdentitySource.ICCID)
            val activeSim =
                ActiveSimInfo(identity, 0, 1, "MTS", "MTS", "25001", isDefaultData = true, isDefaultVoice = true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            // Mock failure
            whenever(migrationService.migrateLegacyData(any()))
                .thenReturn(IdentityMigrationService.MigrationOutcome(failures = 1))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(migrationService, never()).markSchemaCurrent()
        }

    @Test
    fun `doWork completes migration after max retries even with failures`() =
        runTest {
            // Setup worker with attempt count 3
            val worker = TestListenableWorkerBuilder<InitialSetupWorker>(context)
                .setRunAttemptCount(3)
                .setWorkerFactory(
                    object : androidx.work.WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: androidx.work.WorkerParameters,
                        ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, deps)
                    },
                ).build()

            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(value = false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(value = false))
            val identity = SimIdentity("icc_123", 1, 0, IdentitySource.ICCID)
            val activeSim =
                ActiveSimInfo(identity, 0, 1, "MTS", "MTS", "25001", isDefaultData = true, isDefaultVoice = true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult.Success(
                    mock()
                )
            )

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            // Mock failure
            whenever(migrationService.migrateLegacyData(any()))
                .thenReturn(IdentityMigrationService.MigrationOutcome(failures = 1))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationService).markSchemaCurrent()
        }
}
