package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageWorkerDependencies
    @Inject
    constructor(
        val simUsageRepository: SimUsageRepository,
        val simNotificationFlagsRepository: SimNotificationFlagsRepository,
        val appSettingsRepository: AppSettingsRepository,
        val autoResetUseCase: AutoResetUseCase,
        val trafficTracker: TrafficTracker,
        val notificationHelper: NotificationHelper,
        val dataChangeNotifier: DataChangeNotifier,
        val usageStateRepository: UsageStateRepository,
        val usageUseCase: UsageUseCase,
        val timeProvider: TimeProvider,
    )
