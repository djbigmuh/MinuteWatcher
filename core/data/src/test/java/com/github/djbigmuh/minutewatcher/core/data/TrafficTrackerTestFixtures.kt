package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import com.github.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import kotlinx.coroutines.CoroutineScope
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

internal suspend fun createTrafficTrackerDependencies(
    simIdentityRepository: SimIdentityRepository,
    simUsageRepository: SimUsageRepository,
    simUsageResetRepository: SimUsageResetRepository,
    permissionManager: PermissionManager,
    baselineManager: TrafficBaselineManager,
    deltaCalculator: TrafficDeltaCalculator = TrafficDeltaCalculator(simUsageResetRepository),
    simChangeHandler: TrafficSimChangeHandler,
    targetResolver: TrafficTargetResolver = TrafficTargetResolver(permissionManager),
    permissionChecker: TrafficPermissionChecker = TrafficPermissionChecker(permissionManager),
    permissionStateNotifier: PermissionStateNotifier,
    dataChangeNotifier: DataChangeNotifier,
    timeProvider: TimeProvider,
): TrafficTrackerDependencies {
    if (Mockito.mockingDetails(simUsageResetRepository).isMock) {
        try {
            whenever(simUsageResetRepository.ensureTrackingWindowInitialized(any())).thenReturn(true)
            whenever(simUsageResetRepository.setLastTotalBytes(any(), any(), any(), any())).thenReturn(true)
            whenever(
                simUsageResetRepository.incrementTrafficAndSetBaseline(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                ),
            ).thenReturn(true)
        } catch (_: Exception) {
            // Not a mock or not stubbable
        }
    }
    return TrafficTrackerDependencies(
        simIdentityRepository = simIdentityRepository,
        simUsageRepository = simUsageRepository,
        simUsageResetRepository = simUsageResetRepository,
        permissionManager = permissionManager,
        baselineManager = baselineManager,
        deltaCalculator = deltaCalculator,
        simChangeHandler = simChangeHandler,
        targetResolver = targetResolver,
        permissionChecker = permissionChecker,
        permissionStateNotifier = permissionStateNotifier,
        dataChangeNotifier = dataChangeNotifier,
        timeProvider = timeProvider,
    )
}

internal fun createTrafficTracker(
    deps: TrafficTrackerDependencies,
    scope: CoroutineScope,
): TrafficTracker =
    TrafficTracker(deps = deps, scope = scope).apply {
        ensureStarted()
    }
