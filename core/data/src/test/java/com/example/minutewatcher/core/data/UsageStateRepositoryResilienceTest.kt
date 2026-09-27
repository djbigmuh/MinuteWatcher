package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.semantic.SemanticCapabilities
import com.example.minutewatcher.core.data.semantic.SemanticVersion
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class UsageStateRepositoryResilienceTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val semanticCapabilities: SemanticCapabilities = mock()
    private val semanticVersion: SemanticVersion = mock()
    private val timeProvider: TimeProvider = mock()
    private val context: Context = mock()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var deps: UsageRepositoryDependencies

    @Before
    fun setup() {
        initTestLogger()
        whenever(dataRefreshNotifier.refreshFlow).thenReturn(MutableSharedFlow())
        whenever(semanticVersion.getVersion()).thenReturn(SchemaVersion(1, 0, 0))
        whenever(semanticCapabilities.getCapabilities()).thenReturn(emptyList())

        deps =
            UsageRepositoryDependencies(
                simIdentityRepository = simIdentityRepository,
                simUsageRepository = simUsageRepository,
                semanticCapabilities = semanticCapabilities,
                semanticVersion = semanticVersion,
                dataRefreshNotifier = dataRefreshNotifier,
                timeProvider = timeProvider,
            )
    }

    @Test
    fun `test retry logic after IOException`() =
        runTest(testDispatcher) {
            var attempts = 0
            val simFlow =
                flow {
                    attempts++
                    if (attempts == 1) {
                        throw IOException("Simulated failure")
                    }
                    emit(listOf(createSimInfo("sim_1")))
                }
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(simFlow)
            whenever(simUsageRepository.getSimUsage(any())).thenReturn(flow { emit(createSimUsage("sim_1")) })

            val repo = UsageStateRepositoryImpl(context, deps, backgroundScope)

            // Advance time to allow for retry (INITIAL_RETRY_DELAY_MS = 1000)
            advanceTimeBy(1500.milliseconds)
            runCurrent()

            val models = repo.observeSimCardUiModels().first { it.isNotEmpty() }
            assertEquals(1, models.size)
            assertTrue(repo.isDataLoaded())
        }

    @Test
    fun `test status goes to DATABASE_ERROR after consecutive failures`() =
        runTest(testDispatcher) {
            whenever(
                simIdentityRepository.observeActiveSims(),
            ).thenReturn(flow { throw IOException("Persistent failure") })

            val repo = UsageStateRepositoryImpl(context, deps, backgroundScope)

            // LOG_THRESHOLD = 5. Delay: 1s, 2s, 4s, 8s, 16s. Total ~31s.
            advanceTimeBy(40000.milliseconds)
            runCurrent()

            val status = repo.status.value
            assertTrue("Status should be Error, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)

            // Now recover
            whenever(
                simIdentityRepository.observeActiveSims(),
            ).thenReturn(flow { emit(listOf(createSimInfo("sim_1"))) })
            whenever(simUsageRepository.getSimUsage(any())).thenReturn(flow { emit(createSimUsage("sim_1")) })

            advanceTimeBy(40000.milliseconds)
            runCurrent()

            assertEquals(TrackingStatus.Active, repo.status.value)
        }

    @Test
    fun `retry delay remains bounded at maximum delay during long persistent failures`() =
        runTest(testDispatcher) {
            var attemptCount = 0
            val failingFlow =
                flow<List<ActiveSimInfo>> {
                    attemptCount++
                    throw IOException("Simulated persistent failure")
                }
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(failingFlow)

            val repo = UsageStateRepositoryImpl(context, deps, backgroundScope)
            runCurrent()

            var stepCount = 0
            while ((attemptCount < 31) && (stepCount < 1000)) {
                advanceTimeBy(1.seconds)
                runCurrent()
                stepCount++
            }
            assertEquals(31, attemptCount)

            val attemptsBeforeWindow = attemptCount
            advanceTimeBy(61.seconds)
            runCurrent()

            val newAttempts = attemptCount - attemptsBeforeWindow
            assertEquals(2, newAttempts)
            assertTrue(repo.status.value is TrackingStatus.Error)
        }

    private fun createSimInfo(identity: String) =
        ActiveSimInfo(
            identity = SimIdentity(identity, 1, 0, IdentitySource.ICCID, true),
            subscriptionId = 1,
            slotIndex = 0,
            iccId = identity,
            displayName = "SIM",
            carrierName = "Carrier",
            mccMnc = "25001",
            isDefaultData = true,
            isDefaultVoice = true,
        )

    private fun createSimUsage(identity: String) =
        SimUsage(
            identity = identity,
            slotIndex = 0,
            subscriptionId = 1,
            operator = "MTS",
            minutesUsed = 0,
            minutesLimit = 100,
            trafficUsedMb = 0f,
            trafficLimitMb = 1000,
            manualAdjustment = 0,
            trafficAdjustmentGb = 0f,
            autoReset = true,
            autoResetDay = 1,
            lastResetDate = 0L,
        )
}
