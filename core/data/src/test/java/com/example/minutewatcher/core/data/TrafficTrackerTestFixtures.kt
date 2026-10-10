package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any

internal fun createTrafficTrackerDependencies(
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
    runBlocking {
        try {
            org.mockito.kotlin
                .whenever(simUsageResetRepository.ensureTrackingWindowInitialized(any()))
                .thenReturn(true)
            org.mockito.kotlin
                .whenever(simUsageResetRepository.setLastTotalBytes(any(), any(), any()))
                .thenReturn(true)
            org.mockito.kotlin
                .whenever(
                    simUsageResetRepository.incrementTrafficAndSetBaseline(
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
