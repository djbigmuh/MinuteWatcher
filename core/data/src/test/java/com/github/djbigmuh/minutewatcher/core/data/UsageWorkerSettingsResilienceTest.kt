package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.AutoResetUseCase
import com.github.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import com.github.djbigmuh.minutewatcher.core.domain.RefreshResult
import com.github.djbigmuh.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageUseCase
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.DataSourceType
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.MeasurementUnit
import com.github.djbigmuh.minutewatcher.core.model.PackageType
import com.github.djbigmuh.minutewatcher.core.model.SchemaVersion
import com.github.djbigmuh.minutewatcher.core.model.SemanticEntity
import com.github.djbigmuh.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UsageWorkerSettingsResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val simUsageRepository: SimUsageRepository = mock()
    private val simNotificationFlagsRepository: SimNotificationFlagsRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val trafficTracker: TrafficTracker = mock()
    private val notificationHelper: NotificationHelper = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)

    private val testDispatcher get() = mainDispatcherRule.testDispatcher

    @Before
    fun setup() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 1000L,
                simSnapshots =
                    listOf(
                        SimSnapshot(
                            slot = 0,
                            identity = "sim_0",
                            operatorName = "Carrier",
                            entities =
                                mapOf(
                                    PackageType.MINUTES to
                                        SemanticEntity(
                                            id = "e1",
                                            simSlot = 0,
                                            operatorName = "Carrier",
                                            packageType = PackageType.MINUTES,
                                            limit = 100,
                                            used = 90,
                                            remaining = 10,
                                            unit = MeasurementUnit.MINUTES,
                                            periodStart = 0,
                                            periodEnd = 0,
                                            lastUpdateTime = 0,
                                            sourceType = DataSourceType.SYSTEM,
                                        ),
                                ),
                            autoResetDay = 1,
                        ),
                    ),
                capabilities = emptyList(),
            )
        runTest {
            whenever(trafficTracker.updateTrafficUsage()).thenReturn(TrafficUpdateOutcome.Completed)
            whenever(usageStateRepository.refresh()).thenReturn(
                RefreshResult.Success(snapshot),
            )
            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
        }
    }

    @Test
    fun `usage worker does not send notifications on settings load failure under fail closed policy`() =
        runTest {
            whenever(appSettingsRepository.getAppSettings()).thenReturn(
                flow {
                    delay(60_000.milliseconds)
                    emit(AppSettings())
                },
            )

            val context = ApplicationProvider.getApplicationContext<Context>()
            val worker =
                TestListenableWorkerBuilder<UsageCheckWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker {
                                val deps =
                                    UsageWorkerDependencies(
                                        simNotificationFlagsRepository,
                                        appSettingsRepository,
                                        autoResetUseCase,
                                        trafficTracker,
                                        notificationHelper,
                                        dataChangeNotifier,
                                        usageStateRepository,
                                        usageUseCase,
                                        timeProvider,
                                    )
                                return UsageCheckWorker(
                                    appContext,
                                    workerParameters,
                                    deps,
                                    testDispatcher,
                                )
                            }
                        },
                    ).build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)

            verify(notificationHelper, never()).sendUsageNotification(
                any(),
                any(),
                any(),
                any(),
            )
        }
}
