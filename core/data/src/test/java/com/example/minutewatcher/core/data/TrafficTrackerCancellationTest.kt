package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerCancellationTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val baselineManager: TrafficBaselineManager = mock()
    private val deltaCalculator: TrafficDeltaCalculator = mock()
    private val simChangeHandler: TrafficSimChangeHandler = mock()
    private val targetResolver: TrafficTargetResolver = mock()
    private val permissionChecker: TrafficPermissionChecker = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider: TimeProvider = mock()

    private lateinit var trafficTracker: TrafficTracker
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        initTestLogger()
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow())
    }

    @Test
    fun `test cancellation during finally withLock does not cause isInitializing leak`() =
        runTest(testDispatcher) {
            trafficTracker =
                TrafficTracker(
                    simIdentityRepository,
                    simUsageRepository,
                    simUsageResetRepository,
                    permissionManager,
                    baselineManager,
                    deltaCalculator,
                    simChangeHandler,
                    targetResolver,
                    permissionChecker,
                    permissionStateNotifier,
                    dataChangeNotifier,
                    timeProvider,
                    backgroundScope,
                )

            // 1. Get the mutex via reflection
            val mutexField: Field = TrafficTracker::class.java.getDeclaredField("initMutex")
            mutexField.isAccessible = true
            val initMutex = mutexField.get(trafficTracker) as Mutex

            val flagField: Field = TrafficTracker::class.java.getDeclaredField("isInitializing")
            flagField.isAccessible = true
            val isInitializing = flagField.get(trafficTracker) as AtomicBoolean

            // Set state so recover() tries to initialize
            val initFailedField: Field = TrafficTracker::class.java.getDeclaredField("initializationFailed")
            initFailedField.isAccessible = true
            (initFailedField.get(trafficTracker) as AtomicBoolean).set(true)
            isInitializing.set(false)

            val reachedInit = CompletableDeferred<Unit>()
            val simInfo = createSimInfo("sim_1")
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(simInfo)))

            whenever(baselineManager.cleanupRetries(any())).thenAnswer {
                reachedInit.complete(Unit)
            }

            val job =
                launch {
                    trafficTracker.recover()
                }

            reachedInit.await()
            runCurrent()
            initMutex.lock() // Block the finally block

            job.cancelAndJoin() // Cancel while it's waiting for mutex in finally

            initMutex.unlock()

            // 2. Check if flag leaked.
            assert(!isInitializing.get()) { "isInitializing flag should have been cleared to false" }

            // Ensure no leftover coroutines in backgroundScope if any were launched by startAutomaticRecoveryObservers
            // (In this test we only care about the one we launched)
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
}
