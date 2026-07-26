package com.example.minutewatcher.core.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.annotation.Config
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageCheckWorkerModuleTest {
    @Mock lateinit var simRepository: SimRepository

    @Mock lateinit var settingsRepository: SettingsRepository

    @Mock lateinit var resetManager: ResetManager

    @Mock lateinit var trafficTracker: TrafficTracker

    @Mock lateinit var notificationHelper: NotificationHelper

    @Mock lateinit var updateCoordinator: UpdateCoordinator

    @Mock lateinit var usageStateRepository: UsageStateRepository

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
            whenever(settingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
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
                                    simRepository,
                                    settingsRepository,
                                    resetManager,
                                    trafficTracker,
                                    notificationHelper,
                                    updateCoordinator,
                                    usageStateRepository,
                                )
                        },
                    ).build()

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            
            // Verify refresh was called twice as per checkUsageAndNotify logic
            verify(usageStateRepository, times(2)).refresh()
        }
}
