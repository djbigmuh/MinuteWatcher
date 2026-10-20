package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.AutoResetUseCase
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimNotificationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageUseCase
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
