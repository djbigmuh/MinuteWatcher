package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
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
    )
