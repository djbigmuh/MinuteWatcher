package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import com.github.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dependency aggregator for [TrafficTracker].
 *
 * Consolidates dependencies required by [TrafficTracker] to simplify constructor signature
 * and maintain clean separation of concerns.
 */
@Singleton
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
