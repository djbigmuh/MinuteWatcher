package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.AutoResetUseCase
import com.github.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageUseCase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageWorkerDependencies
    @Inject
    constructor(
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
