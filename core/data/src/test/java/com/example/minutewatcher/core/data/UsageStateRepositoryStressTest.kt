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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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

    private val refreshFlow = MutableSharedFlow<DataRefreshNotifier.RefreshEvent>()
    private val currentTime = AtomicInteger(1000)

    @Before
    fun setup() {
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(emptyList()))
        whenever(semanticVersion.getVersion()).thenReturn(SchemaVersion(1, 0, 0))
        whenever(semanticCapabilities.getCapabilities()).thenReturn(emptyList())
        whenever(dataRefreshNotifier.refreshFlow).thenReturn(refreshFlow.asSharedFlow())

        // Simulate time advancing on each call to ensure unique snapshots
        whenever(timeProvider.now()).thenAnswer { currentTime.incrementAndGet().toLong() }

        // Mock notifyRefresh to emit to the flow with a small delay to test concurrency
        dataRefreshNotifier.stub {
            onBlocking { notifyRefresh(any(), any()) } doAnswer {
                val isManual = it.getArguments()[0] as Boolean
                runBlocking {
                    refreshFlow.emit(DataRefreshNotifier.RefreshEvent(isManual = isManual))
                }
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
                    async(Dispatchers.Default) {
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

            assertTrue("Expected at least one success", successCount > 0)
            assertTrue("All calls should complete without deadlock", (successCount + timeoutCount) == 100)

            // Verify notifyRefresh was called, but likely fewer than 100 times due to tryLock
            verify(dataRefreshNotifier, atLeastOnce()).notifyRefresh(eq(true), any())
        }
}
