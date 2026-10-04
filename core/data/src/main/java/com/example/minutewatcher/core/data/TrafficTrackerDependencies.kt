package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dependency aggregator for [TrafficTracker].
 *
 * Consolidates dependencies required by [TrafficTracker] to simplify constructor signature
 * and maintain clean separation of concerns.
 */
@Singleton
// Suppressing LongParameterList is justified: TrafficTrackerDependencies is a helper class
// for dependency injection (Dependency Aggregator). This avoids inflating the parameter list
// of TrafficTracker constructor while preserving testability and separation of concerns.
@Suppress("LongParameterList")
class TrafficTrackerDependencies
@Inject
constructor(
    val simIdentityRepository: SimIdentityRepository,
    val simUsageRepository: SimUsageRepository,
    val simUsageResetRepository: SimUsageResetRepository,
    val permissionManager: PermissionManager,
    val baselineManager: TrafficBaselineManager,
    val deltaCalculator: TrafficDeltaCalculator,
    val simChangeHandler: TrafficSimChangeHandler,
    val targetResolver: TrafficTargetResolver,
    val permissionChecker: TrafficPermissionChecker,
    val permissionStateNotifier: PermissionStateNotifier,
    val dataChangeNotifier: DataChangeNotifier,
    val timeProvider: TimeProvider,
)
