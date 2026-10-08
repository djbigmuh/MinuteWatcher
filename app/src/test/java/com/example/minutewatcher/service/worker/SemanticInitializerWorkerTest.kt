package com.example.minutewatcher.service.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(application = com.example.minutewatcher.MinuteWatcherTestApp::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticInitializerWorkerTest {
    private lateinit var context: Context
    private val searchManager: SemanticSearchManager = mock()
    private val shortcutManager: ShortcutManager = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val appSettingsRepository: com.example.minutewatcher.core.domain.AppSettingsRepository = mock()
    private val appFunctionAvailability: com.example.minutewatcher.core.domain.AppFunctionAvailability = mock()
    private val snapshotFlow = MutableStateFlow(createEmptySnapshot())

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
        whenever(appSettingsRepository.getAppSettings()).thenReturn(kotlinx.coroutines.flow.flowOf(com.example.minutewatcher.core.model.AppSettings()))
        runBlocking {
            whenever(searchManager.indexInitialSnapshot(any())).thenReturn(SemanticSearchManager.IndexResult.Success)
            whenever(shortcutManager.updateShortcutsSync(any())).thenReturn(true)
        }
    }

    @Test
    fun `worker waits for non-empty snapshot and triggers indexing`() =
        runTest {
            val worker =
                TestListenableWorkerBuilder<SemanticInitializerWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker =
                                SemanticInitializerWorker(
                                    appContext,
                                    workerParameters,
                                    searchManager,
                                    shortcutManager,
                                    usageStateRepository,
                                    appSettingsRepository,
                                    appFunctionAvailability,
                                )
                        },
                    ).build()

            val job =
                launch {
                    val result = worker.doWork()
                    assertEquals(ListenableWorker.Result.success(), result)
                }

            // Initially empty snapshot is emitted by StateFlow
            // Worker should be waiting...
            runCurrent()
            verify(searchManager, never()).indexInitialSnapshot(any())

            // Emit meaningful snapshot
            val realSnapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 1000L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            snapshotFlow.value = realSnapshot

            withTimeoutOrNull(30.seconds) { job.join() }
                ?: throw AssertionError("Timed out waiting for worker job to complete")

            verify(searchManager).indexInitialSnapshot(realSnapshot)
            verify(shortcutManager).updateShortcutsSync(realSnapshot)
        }

    private fun createEmptySnapshot() =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 0L,
            simSnapshots = emptyList(),
            capabilities = emptyList(),
        )
}
