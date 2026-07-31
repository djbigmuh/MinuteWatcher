package com.example.minutewatcher.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InitialSetupWorkerTest {
    private val context: Context = mock()
    private val params: WorkerParameters = mock()
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

    private lateinit var worker: InitialSetupWorker

    @Before
    fun setup() {
        whenever(context.applicationContext).thenReturn(context)
        whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(true))
        whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(true))
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

        val emptySnapshot =
            com.example.minutewatcher.core.model.GlobalSnapshot(
                version =
                    com.example.minutewatcher.core.model
                        .SchemaVersion(1, 0, 0),
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

        worker =
            InitialSetupWorker(
                context,
                params,
                callTracker,
                trafficTracker,
                simIdentityRepository,
                migrationFlagsRepository,
                usageStateRepository,
                autoResetUseCase,
                usageUseCase,
                migrationService,
                dataChangeNotifier,
                workScheduler,
            )
    }

    @Test
    fun `doWork performs auto-reset after migration`() =
        runTest {
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(autoResetUseCase).performAutoReset(any())
        }

    @Test
    fun `doWork returns success when migration already completed`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(true))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(true))
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            val result = worker.doWork()

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

            mockStatic(ContextCompat::class.java).use { mockedContext ->
                mockedContext
                    .`when`<Int> {
                        ContextCompat.checkSelfPermission(any(), org.mockito.kotlin.eq(Manifest.permission.READ_PHONE_STATE))
                    }.thenReturn(PackageManager.PERMISSION_DENIED)

                val result = worker.doWork()

                assertEquals(ListenableWorker.Result.success(), result)
                verify(migrationFlagsRepository).setMigrationV1Completed(true)
                verify(migrationFlagsRepository).setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                verify(migrationFlagsRepository).setMigrationIdentityStabilityCompleted(true)
                verify(migrationService).markSchemaCurrent()
            }
        }

    @Test
    fun `doWork defers migration when no permission but legacy data exists`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(true)

            mockStatic(ContextCompat::class.java).use { mockedContext ->
                mockedContext
                    .`when`<Int> {
                        ContextCompat.checkSelfPermission(any(), org.mockito.kotlin.eq(Manifest.permission.READ_PHONE_STATE))
                    }.thenReturn(PackageManager.PERMISSION_DENIED)

                val result = worker.doWork()

                assertEquals(ListenableWorker.Result.success(), result)
                verify(migrationFlagsRepository, org.mockito.kotlin.never()).setMigrationV1Completed(true)
                verify(migrationService, org.mockito.kotlin.never()).markSchemaCurrent()
            }
        }

    @Test
    fun `doWork defers migration when permission granted but no active sims and legacy data exists`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
            whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(false))
            whenever(migrationService.hasLegacyData(any())).thenReturn(true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            mockStatic(ContextCompat::class.java).use { mockedContext ->
                mockedContext
                    .`when`<Int> {
                        ContextCompat.checkSelfPermission(any(), org.mockito.kotlin.eq(Manifest.permission.READ_PHONE_STATE))
                    }.thenReturn(PackageManager.PERMISSION_GRANTED)

                val result = worker.doWork()

                assertEquals(ListenableWorker.Result.success(), result)
                verify(migrationFlagsRepository, org.mockito.kotlin.never()).setMigrationV1Completed(true)
                verify(migrationService, org.mockito.kotlin.never()).markSchemaCurrent()
            }
        }

    @Test
    fun `doWork performs migration when active sims found`() =
        runTest {
            whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
            val identity = SimIdentity("icc_123", 1, 0, IdentitySource.ICCID)
            val activeSim = ActiveSimInfo(identity, 0, 1, "MTS", "MTS", "25001", true, true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))

            mockStatic(ContextCompat::class.java).use { mockedContext ->
                mockedContext
                    .`when`<Int> {
                        ContextCompat.checkSelfPermission(any(), org.mockito.kotlin.eq(Manifest.permission.READ_PHONE_STATE))
                    }.thenReturn(PackageManager.PERMISSION_GRANTED)

                val result = worker.doWork()

                assertEquals(ListenableWorker.Result.success(), result)
                verify(migrationService).migrateLegacyData(activeSim.identity)
                verify(migrationFlagsRepository).setMigrationV1Completed(true)
                verify(migrationFlagsRepository).setMigrationIdentityStabilityCompleted(true)
                verify(migrationService).markSchemaCurrent()
            }
        }
}
