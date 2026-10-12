package com.example.minutewatcher.core.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class InitialSetupWorkerBootTest {
    private val bootCompleted = AtomicBoolean(false)
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

        whenever(migrationFlagsRepository.isMigrationV1Completed()).thenReturn(flowOf(true))
        whenever(migrationFlagsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(true))
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

        runTest {
            whenever(callTracker.onBootAwaited()).doSuspendableAnswer {
                delay(1000)
                bootCompleted.set(true)
            }
            whenever(usageStateRepository.refresh()).thenReturn(
                RefreshResult.Success(
                    GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList()),
                ),
            )
            whenever(dataChangeNotifier.notifyDataChanged(any(), any())).thenReturn(true)
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

    @Test
    fun `doWork waits for onBootAwaited to complete`() =
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
                            ) = InitialSetupWorker(appContext, workerParameters, deps)
                        },
                    ).build()

            val result = worker.doWork()

            assertTrue("onBootAwaited should have completed", bootCompleted.get())
            assertEquals(ListenableWorker.Result.success(), result)
            verify(workScheduler).schedulePeriodicUsageCheck()
            verify(migrationFlagsRepository).setInitialSetupCompleted(true)
        }
}
