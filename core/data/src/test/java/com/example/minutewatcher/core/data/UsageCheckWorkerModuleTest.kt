package com.example.minutewatcher.core.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsageCheckWorkerModuleTest {
    @Mock lateinit var simIdentityRepository: SimIdentityRepository
    @Mock lateinit var simUsageRepository: SimUsageRepository
    @Mock lateinit var simNotificationFlagsRepository: SimNotificationFlagsRepository
    @Mock lateinit var appSettingsRepository: AppSettingsRepository
    @Mock lateinit var autoResetUseCase: AutoResetUseCase
    @Mock lateinit var trafficTracker: TrafficTracker
    @Mock lateinit var notificationHelper: NotificationHelper
    @Mock lateinit var dataChangeNotifier: DataChangeNotifier
    @Mock lateinit var usageStateRepository: UsageStateRepository
    @Mock lateinit var usageUseCase: UsageUseCase
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        // Grant permission for Robolectric
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
            whenever(usageStateRepository.refresh()).thenReturn(emptySnapshot)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings()))
        }
    }

    @Test
    fun testDoWork_Success() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val worker =
                TestListenableWorkerBuilder<UsageCheckWorker>(context)
                    .setWorkerFactory(
                        object : androidx.work.WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: androidx.work.WorkerParameters,
                            ): ListenableWorker =
                                UsageCheckWorker(
                                    appContext,
                                    workerParameters,
                                    simIdentityRepository,
                                    simUsageRepository,
                                    simNotificationFlagsRepository,
                                    appSettingsRepository,
                                    autoResetUseCase,
                                    trafficTracker,
                                    notificationHelper,
                                    dataChangeNotifier,
                                    usageStateRepository,
                                    usageUseCase,
                                    timeProvider,
                                    kotlinx.coroutines.test.UnconfinedTestDispatcher(),
                                )
                        },
                    ).build()

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)

            // Verify refresh was called twice as per checkUsageAndNotify logic
            verify(usageStateRepository, times(2)).refresh()
        }
}


