package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.lang.reflect.Field

@OptIn(ExperimentalCoroutinesApi::class)
class ResetManagerCancellationTest {

    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val trafficTracker: TrafficTracker = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider = FixedTimeProvider()

    private lateinit var resetManager: ResetManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        initTestLogger()
        resetManager = ResetManager(
            simIdentityRepository,
            simUsageRepository,
            simUsageResetRepository,
            trafficTracker,
            dataChangeNotifier,
            timeProvider
        )
    }

    @Test
    fun `test cancellation during finally withLock does not cause flag leak`() = runTest(testDispatcher) {
        val identity = "sim_1"
        val simInfo = createSimInfo(identity)
        val usage = createSimUsage(identity)

        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(simInfo)))

        // 1. Get the mutex via reflection
        val mutexField: Field = ResetManager::class.java.getDeclaredField("resetMutex")
        mutexField.isAccessible = true
        val resetMutex = mutexField.get(resetManager) as Mutex

        val reachedCheck = CompletableDeferred<Unit>()
        whenever(simUsageRepository.getSimUsage(identity)).thenAnswer {
            reachedCheck.complete(Unit)
            flowOf(usage)
        }

        val job = launch {
            resetManager.performAutoReset(false)
        }

        reachedCheck.await()
        runCurrent()
        resetMutex.lock() // Block the finally block

        job.cancelAndJoin() // Cancel while it's waiting for mutex in finally

        resetMutex.unlock()

        // 2. Now try to run again. If flag did NOT leak, it will proceed.
        timeProvider.advance(3600000) // Pass cooldown

        resetManager.performAutoReset(false)
        advanceUntilIdle()

        // Verify it was called twice (once in the cancelled run, once in the successful run)
        verify(simUsageResetRepository, atLeast(2)).resetSimUsageIfDue(eq(identity), any(), any())
    }

    private fun createSimInfo(identity: String) = ActiveSimInfo(
        identity = SimIdentity(identity, 1, 0, IdentitySource.ICCID, true),
        subscriptionId = 1,
        slotIndex = 0,
        iccId = identity,
        displayName = "SIM",
        carrierName = "Carrier",
        mccMnc = "25001",
        isDefaultData = true,
        isDefaultVoice = true
    )

    private fun createSimUsage(identity: String) = SimUsage(
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
        lastResetDate = 0L
    )
}
