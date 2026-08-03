package com.example.minutewatcher.core.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.MigrationSkipReason
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.never
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    private lateinit var deps: InitialSetupWorkerDependencies
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Application>()
        whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(true))
        whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(true))
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

        val emptySnapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 0L,
                simSnapshots = emptyList(),
                capabilities = emptyList(),
            )
            
        runTest {
            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult
                    .Success(emptySnapshot),
            )
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
            )
    }

    private fun createWorker(): InitialSetupWorker {
        return TestListenableWorkerBuilder<InitialSetupWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters
                ): ListenableWorker = InitialSetupWorker(appContext, workerParameters, deps)
            }).build()
    }

    @Test
    fun `doWork performs auto-reset after migration`() =
        runTest {
            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(autoResetUseCase).performAutoReset(any())
        }

    @Test
    fun `doWork returns success when migration already completed`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(true))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(true))
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(usageStateRepository, times(2)).refresh()
            verify(dataChangeNotifier).notifyDataChanged(updateWidget = true, force = true)
        }

    @Test
    fun `doWork skips migration V1 when no permission and no legacy data`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(false)

            Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_PHONE_STATE)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationFlagsRepository).setMigrationV1Completed(true)
            verify(migrationFlagsRepository).setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
            verify(migrationFlagsRepository).setMigrationIdentityStabilityCompleted(true)
            verify(migrationService).markSchemaCurrent()
        }

    @Test
    fun `doWork defers migration when no permission but legacy data exists`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(false))
            whenever(migrationService.hasLegacyData()).thenReturn(true)

            Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_PHONE_STATE)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationFlagsRepository, never()).setMigrationV1Completed(true)
            verify(migrationService, never()).markSchemaCurrent()
        }

    @Test
    fun `doWork defers migration when permission granted but no active sims and legacy data exists`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationFlagsRepository, never()).setMigrationV1Completed(true)
            verify(migrationService, never()).markSchemaCurrent()
        }

    @Test
    fun `doWork performs migration when active sims found`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
            val identity = SimIdentity("icc_123", 1, 0, IdentitySource.ICCID)
            val activeSim = ActiveSimInfo(identity, 0, 1, "MTS", "MTS", "25001", true, true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))

            Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(migrationService).migrateLegacyData(activeSim.identity)
            verify(migrationFlagsRepository).setMigrationV1Completed(true)
            verify(migrationFlagsRepository).setMigrationIdentityStabilityCompleted(true)
            verify(migrationService).markSchemaCurrent()
        }
}
