package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.semantic.SemanticCapabilities
import com.example.minutewatcher.core.data.semantic.SemanticVersion
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class UsageStateRepositoryImplTest {
    private val context: Context = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val semanticCapabilities: SemanticCapabilities = mock()
    private val semanticVersion: SemanticVersion = mock()
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1000L)

    @Before
    fun setup() {
        initTestLogger()
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(emptyList()))
        whenever(dataRefreshNotifier.refreshFlow).thenReturn(MutableSharedFlow())
        whenever(semanticVersion.getVersion()).thenReturn(SchemaVersion(1, 0, 0))
        whenever(semanticCapabilities.getCapabilities()).thenReturn(emptyList())
    }

    @Test
    fun `refresh finishes within timeout when snapshot does not update`() =
        runTest {
            // We use a mock that doesn't emit to refreshFlow, so snapshotFlow will never update
            whenever(dataRefreshNotifier.refreshFlow).thenReturn(MutableSharedFlow())

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps =
                        UsageRepositoryDependencies(
                            simIdentityRepository = simIdentityRepository,
                            simUsageRepository = simUsageRepository,
                            semanticCapabilities = semanticCapabilities,
                            semanticVersion = semanticVersion,
                            dataRefreshNotifier = dataRefreshNotifier,
                            timeProvider = timeProvider,
                        ),
                    scope = backgroundScope,
                )

            val startTime = testScheduler.currentTime
            val result = repository.refresh()
            val endTime = testScheduler.currentTime

            assertTrue("Result should be Timeout, was $result", result is RefreshResult.Timeout)
            val duration = endTime - startTime
            // SNAPSHOT_TIMEOUT_MS is 2000ms.
            assertTrue(
                "Refresh should finish within timeout (duration: $duration)",
                duration <=
                    Constants.Data.SNAPSHOT_TIMEOUT_MS +
                    TIMEOUT_TOLERANCE_MS,
            )
            assertTrue(
                "Refresh should take at least the timeout duration (duration: $duration)",
                duration >=
                    Constants.Data.SNAPSHOT_TIMEOUT_MS,
            )
        }

    companion object {
        private const val TIMEOUT_TOLERANCE_MS = 500L
    }
}
