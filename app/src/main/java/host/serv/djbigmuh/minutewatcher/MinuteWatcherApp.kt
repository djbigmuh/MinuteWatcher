package host.serv.djbigmuh.minutewatcher

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.data.AodSummaryController
import host.serv.djbigmuh.minutewatcher.core.data.CallTracker
import host.serv.djbigmuh.minutewatcher.core.data.NotificationHelper
import host.serv.djbigmuh.minutewatcher.core.data.TrafficTracker
import host.serv.djbigmuh.minutewatcher.core.data.UpdateCoordinator
import host.serv.djbigmuh.minutewatcher.feature.widget.WidgetLimitManager
import host.serv.djbigmuh.minutewatcher.service.worker.SemanticInitScheduler
import javax.inject.Inject

@HiltAndroidApp
class MinuteWatcherApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * Injected to eagerly start call tracking in [onCreate].
     */
    @Inject
    lateinit var callTracker: CallTracker

    /**
     * Injected to eagerly start traffic tracking in [onCreate].
     */
    @Inject
    lateinit var trafficTracker: TrafficTracker

    /**
     * Injected to explicitly start AOD summary notification controller observation in [onCreate].
     * Constructor has no side effects.
     */
    @Inject
    lateinit var aodSummaryController: AodSummaryController

    /**
     * Injected to explicitly start widget limit flag observation in [onCreate].
     * Constructor has no side effects.
     */
    @Inject
    lateinit var widgetLimitManager: WidgetLimitManager

    /**
     * Injected to explicitly start widget update observation in [onCreate].
     * Constructor has no side effects.
     */
    @Inject
    lateinit var updateCoordinator: UpdateCoordinator

    /**
     * Injected to ensure notification channels exist on app startup.
     */
    @Inject
    lateinit var notificationHelper: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        notificationHelper.ensureChannels()

        WorkManager.initialize(this, workManagerConfiguration)

        // Eagerly start trackers to ensure they are ready for events and UI observation.
        callTracker.ensureStarted()
        trafficTracker.ensureStarted()

        SemanticInitScheduler.schedule(WorkManager.getInstance(this))
        aodSummaryController.start()
        widgetLimitManager.start()
        updateCoordinator.start()

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy
                    .Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
