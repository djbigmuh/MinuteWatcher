package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerRecoveryTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val baselineManager: TrafficBaselineManager = mock()
    private val deltaCalculator: TrafficDeltaCalculator = mock()
    private val simChangeHandler: TrafficSimChangeHandler = mock()
    private val targetResolver: TrafficTargetResolver = mock()
    private val errorHandler: TrafficErrorHandler = mock()
    private val permissionChecker: TrafficPermissionChecker = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()

    private val permissionChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val simFlow = MutableSharedFlow<List<ActiveSimInfo>>(extraBufferCapacity = 1)

    @Before
    fun setup() {
        initTestLogger()
        whenever(permissionChecker.checkPermissions(any())).thenReturn(true)
        whenever(permissionStateNotifier.changes).thenReturn(permissionChanges)

        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
    }

    private fun createTracker(scope: TestScope): TrafficTracker =
        TrafficTracker(
            simIdentityRepository = simIdentityRepository,
            simUsageRepository = simUsageRepository,
            simUsageResetRepository = simUsageResetRepository,
            permissionManager = permissionManager,
            baselineManager = baselineManager,
            deltaCalculator = deltaCalculator,
            simChangeHandler = simChangeHandler,
            targetResolver = targetResolver,
            errorHandler = errorHandler,
            permissionChecker = permissionChecker,
            permissionStateNotifier = permissionStateNotifier,
            dataChangeNotifier = mock(),
            scope = scope.backgroundScope,
        )

    @Test
    fun `automatic recovery observers do not trigger concurrent initializations`() =
        runTest {
            // 1. Setup initial failure for 3 attempts
            var callCount = 0
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                callCount++
                if (callCount <= 4) { // 1 for SIM observer + 3 for init attempts
                    error("Initial failure")
                }
                simFlow
            }

            createTracker(this)
            runCurrent()

            // Let all 3 init attempts fail
            advanceTimeBy(10000.milliseconds)
            runCurrent()

            // Verify initial 4 calls (1 observer, 3 attempts)
            verify(simIdentityRepository, times(4)).observeActiveSims().let {}

            // 2. Trigger recovery concurrently from both observers
            // We need to re-mock because the previous thenAnswer is exhausted or would keep throwing
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(simFlow)

            permissionChanges.emit(Unit)
            simFlow.emit(emptyList())
            runCurrent()

            // 3. Assert: only ONE more call to observeActiveSims (from ONE recovery attempt)
            // Total = 4 (init) + 1 (recovery) = 5
            verify(simIdentityRepository, times(5)).observeActiveSims().let {}
            verify(permissionStateNotifier, atLeast(1)).changes.let {}
        }
}
