package com.example.minutewatcher.core.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UsageCheckWorkerModuleTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Mock lateinit var simUsageRepository: SimUsageRepository

    @Mock lateinit var simIdentityRepository: SimIdentityRepository

    @Mock lateinit var simWidgetVisibilityRepository: SimWidgetVisibilityRepository

    @Mock lateinit var simNotificationFlagsRepository: SimNotificationFlagsRepository

    @Mock lateinit var appSettingsRepository: AppSettingsRepository

    @Mock lateinit var autoResetUseCase: AutoResetUseCase

    @Mock lateinit var trafficTracker: TrafficTracker

    @Mock lateinit var notificationHelper: NotificationHelper

    @Mock lateinit var dataChangeNotifier: DataChangeNotifier

    @Mock lateinit var usageStateRepository: UsageStateRepository

    @Mock lateinit var usageUseCase: UsageUseCase
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)

    private val testDispatcher get() = mainDispatcherRule.testDispatcher

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        val application = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

        val emptySnapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 0L,
                simSnapshots = emptyList(),
                capabilities = emptyList(),
            )

        runTest {
            whenever(trafficTracker.updateTrafficUsage()).thenReturn(TrafficUpdateOutcome.Completed)
            whenever(usageStateRepository.refresh()).thenReturn(
                RefreshResult
                    .Success(emptySnapshot),
            )
            whenever(
                usageStateRepository.observeSnapshot(),
            ).thenReturn(MutableStateFlow(emptySnapshot))
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings()))
        }
    }

    @Test
    fun `doWork success`() =
        runTest {
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

            verify(usageStateRepository, times(1)).refresh()
        }
}
