package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.semantic.SemanticCapabilities
import com.example.minutewatcher.core.data.semantic.SemanticVersion
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UsageStateRepositoryStressTest {
    private val context: Context = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val semanticCapabilities: SemanticCapabilities = mock()
    private val semanticVersion: SemanticVersion = mock()
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val timeProvider: TimeProvider = mock()

    private val refreshFlow =
        MutableSharedFlow<DataRefreshNotifier.RefreshEvent>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val currentTime = AtomicInteger(1000)

    @Before
    fun setup() {
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(emptyList()))
        whenever(semanticVersion.getVersion()).thenReturn(SchemaVersion(1, 0, 0))
        whenever(semanticCapabilities.getCapabilities()).thenReturn(emptyList())
        whenever(dataRefreshNotifier.refreshFlow).thenReturn(refreshFlow)

        // Simulate time advancing on each call to ensure unique snapshots
        whenever(timeProvider.now()).thenAnswer { currentTime.incrementAndGet().toLong() }

        // Mock notifyRefresh to emit to the flow. Use tryEmit to avoid suspension in mock.
        dataRefreshNotifier.stub {
            on { notifyRefresh(any(), any()) } doAnswer {
                val isManual = it.arguments[0] as Boolean
                refreshFlow.tryEmit(DataRefreshNotifier.RefreshEvent(isManual = isManual))
                Unit
            }
        }
    }

    @Test
    @Suppress("MagicNumber")
    fun `refresh stress test with 100 parallel calls`() =
        runTest {
            val deps =
                UsageRepositoryDependencies(
                    simIdentityRepository = simIdentityRepository,
                    simUsageRepository = simUsageRepository,
                    semanticCapabilities = semanticCapabilities,
                    semanticVersion = semanticVersion,
                    dataRefreshNotifier = dataRefreshNotifier,
                    timeProvider = timeProvider,
                )

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps = deps,
                    scope = backgroundScope,
                )

            // Warm up: initial state
            assertTrue(repository.observeSnapshot().value.timestamp == 0L)

            // Act: 100 parallel calls
            val results = mutableListOf<Deferred<RefreshResult>>()
            repeat(100) {
                results.add(
                    async {
                        repository.refresh()
                    },
                )
            }

            val completedResults = results.awaitAll()

            // Assert: No deadlocks and all calls returned a result
            assertTrue("Expected 100 results", completedResults.size == 100)

            // Verify success or timeout (mostly success if logic is correct)
            val successCount = completedResults.count { it is RefreshResult.Success }
            val timeoutCount = completedResults.count { it is RefreshResult.Timeout }

            assertTrue("Expected at least one success, but got $successCount", successCount > 0)
            assertTrue("All calls should complete without deadlock", (successCount + timeoutCount) == 100)

            // Verify notifyRefresh was called, but likely fewer than 100 times due to tryLock
            verify(dataRefreshNotifier, atLeastOnce()).notifyRefresh(
                isManual = eq(value = true),
                suppressWidgetUpdate = any()
            )
        }

    @Test
    fun `concurrent refresh calls share the same update and only trigger notify once`() =
        runTest {
            val deps =
                UsageRepositoryDependencies(
                    simIdentityRepository = simIdentityRepository,
                    simUsageRepository = simUsageRepository,
                    semanticCapabilities = semanticCapabilities,
                    semanticVersion = semanticVersion,
                    dataRefreshNotifier = dataRefreshNotifier,
                    timeProvider = timeProvider,
                )

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps = deps,
                    scope = backgroundScope,
                )

            val results = mutableListOf<Deferred<RefreshResult>>()
            repeat(10) {
                results.add(async { repository.refresh() })
            }

            val completedResults = results.awaitAll()

            // All should be successful
            assertTrue(completedResults.all { it is RefreshResult.Success })

            // notifyRefresh should be called only ONCE because others will see the updated timestamp
            verify(
                dataRefreshNotifier,
                org.mockito.kotlin.times(1),
            ).notifyRefresh(isManual = eq(value = true), suppressWidgetUpdate = any())
        }

    @Test
    fun `refresh returns success immediately if snapshot already updated while waiting for lock`() =
        runTest {
            val deps =
                UsageRepositoryDependencies(
                    simIdentityRepository = simIdentityRepository,
                    simUsageRepository = simUsageRepository,
                    semanticCapabilities = semanticCapabilities,
                    semanticVersion = semanticVersion,
                    dataRefreshNotifier = dataRefreshNotifier,
                    timeProvider = timeProvider,
                )

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps = deps,
                    scope = backgroundScope,
                )

            val job1 = async { repository.refresh() }

            // Wait a bit to ensure job1 has the lock
            kotlinx.coroutines.delay(50.milliseconds)

            // 2. Trigger another refresh while job1 is still running
            val job2 = async { repository.refresh() }

            val result1 = job1.await()
            val result2 = job2.await()

            assertTrue(result1 is RefreshResult.Success)
            assertTrue(result2 is RefreshResult.Success)

            // They should have the same snapshot timestamp
            assertEquals(result1.snapshot.timestamp, result2.snapshot.timestamp)

            // notifyRefresh called only once
            verify(
                dataRefreshNotifier,
                org.mockito.kotlin.times(1),
            ).notifyRefresh(isManual = eq(value = true), suppressWidgetUpdate = any())
        }
}
