package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.domain.AutoResetUseCase
import com.github.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import com.github.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageUseCase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// Suppressing LongParameterList is justified: InitialSetupWorkerDependencies is a helper class
// for dependency injection (Dependency Aggregator). This avoids inflating the parameter list
// of InitialSetupWorker constructor while preserving testability and separation of concerns.
@Suppress("LongParameterList")
class InitialSetupWorkerDependencies
    @Inject
    constructor(
        val callTracker: CallTracker,
        val trafficTracker: TrafficTracker,
        val simIdentityRepository: SimIdentityRepository,
        val migrationFlagsRepository: MigrationFlagsRepository,
        val usageStateRepository: UsageStateRepository,
        val autoResetUseCase: AutoResetUseCase,
        val usageUseCase: UsageUseCase,
        val migrationService: IdentityMigrationService,
        val dataChangeNotifier: DataChangeNotifier,
        val workScheduler: WorkScheduler,
        val permissionManager: PermissionManager,
    )
