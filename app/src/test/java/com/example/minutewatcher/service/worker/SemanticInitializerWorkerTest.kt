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
import com.example.minutewatcher.core.model.TrackingStatus
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
        whenever(
            appSettingsRepository.getAppSettings(),
        ).thenReturn(
            kotlinx.coroutines.flow.flowOf(
                com.example.minutewatcher.core.model
                    .AppSettings(),
            ),
        )
        runBlocking {
            whenever(searchManager.indexInitialSnapshot(any())).thenReturn(SemanticSearchManager.IndexResult.Success)
            whenever(shortcutManager.updateShortcutsSync(any())).thenReturn(true)
            whenever(searchManager.status).thenReturn(MutableStateFlow(TrackingStatus.Active))
            whenever(shortcutManager.status).thenReturn(MutableStateFlow(TrackingStatus.Active))
        }
    }

    private fun createWorker(attemptCount: Int = 1): SemanticInitializerWorker =
        TestListenableWorkerBuilder<SemanticInitializerWorker>(context)
            .setRunAttemptCount(attemptCount)
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

    @Test
    fun `worker waits for non-empty snapshot and triggers indexing`() =
        runTest {
            val worker = createWorker(1)

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

    @Test
    fun `actual failure on first attempt retries`() =
        runTest {
            whenever(
                searchManager.indexInitialSnapshot(any()),
            ).thenReturn(SemanticSearchManager.IndexResult.ActualFailure())
            val worker = createWorker(1)
            snapshotFlow.value = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `actual failure on max attempts fails`() =
        runTest {
            whenever(
                searchManager.indexInitialSnapshot(any()),
            ).thenReturn(SemanticSearchManager.IndexResult.ActualFailure())
            val worker = createWorker(3)
            snapshotFlow.value = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.failure(), result)
        }

    @Test
    fun `actual failure with not supported status succeeds`() =
        runTest {
            whenever(
                searchManager.indexInitialSnapshot(any()),
            ).thenReturn(SemanticSearchManager.IndexResult.ActualFailure())
            whenever(
                searchManager.status,
            ).thenReturn(MutableStateFlow(TrackingStatus.Error(TrackingStatus.ErrorType.NOT_SUPPORTED)))
            val worker = createWorker(3)
            snapshotFlow.value = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `mutex contention on first attempt retries`() =
        runTest {
            whenever(
                searchManager.indexInitialSnapshot(any()),
            ).thenReturn(SemanticSearchManager.IndexResult.MutexContention)
            val worker = createWorker(1)
            snapshotFlow.value = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `mutex contention when attempts exhausted succeeds`() =
        runTest {
            whenever(
                searchManager.indexInitialSnapshot(any()),
            ).thenReturn(SemanticSearchManager.IndexResult.MutexContention)
            val worker = createWorker(3)
            snapshotFlow.value = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `shortcut failure on first attempt retries`() =
        runTest {
            whenever(shortcutManager.updateShortcutsSync(any())).thenReturn(false)
            val worker = createWorker(1)
            snapshotFlow.value = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `shortcut failure with not supported status succeeds`() =
        runTest {
            whenever(shortcutManager.updateShortcutsSync(any())).thenReturn(false)
            whenever(
                shortcutManager.status,
            ).thenReturn(MutableStateFlow(TrackingStatus.Error(TrackingStatus.ErrorType.NOT_SUPPORTED)))
            val worker = createWorker(1)
            snapshotFlow.value = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `exception in indexInitialSnapshot does not escape and retries`() =
        runTest {
            whenever(searchManager.indexInitialSnapshot(any())).thenThrow(RuntimeException("Crash"))
            val worker = createWorker(1)
            snapshotFlow.value = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `unexpected exception in executeSemanticInit on attempt below limit retries`() =
        runTest {
            whenever(usageStateRepository.observeSnapshot()).thenThrow(RuntimeException("Unexpected error"))
            val worker = createWorker(1)

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `unexpected exception in executeSemanticInit on attempt at limit fails`() =
        runTest {
            whenever(usageStateRepository.observeSnapshot()).thenThrow(RuntimeException("Unexpected error"))
            val worker = createWorker(3)

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.failure(), result)
        }

    private fun createEmptySnapshot() =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 0L,
            simSnapshots = emptyList(),
            capabilities = emptyList(),
        )
}
