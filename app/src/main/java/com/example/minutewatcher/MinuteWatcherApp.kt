package com.example.minutewatcher

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.CallTracker
import com.example.minutewatcher.core.data.TrafficTracker
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import com.example.minutewatcher.service.worker.SemanticInitializerWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class MinuteWatcherApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var searchManager: SemanticSearchManager

    @Inject lateinit var shortcutManager: ShortcutManager

    @Inject lateinit var usageStateRepository: UsageStateRepository

    @Inject
    lateinit var callTracker: CallTracker

    @Inject
    lateinit var trafficTracker: TrafficTracker

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)

        WorkManager.initialize(this, workManagerConfiguration)

        // Eagerly start trackers to ensure they are ready for events and UI observation.
        callTracker.ensureStarted()

        // Defer semantic layer initialization to improve cold start performance.
        // Using WorkManager ensures that initialization is attempted reliably even if the
        // initial application process is short-lived, while providing a grace period for UI.
        val initRequest =
            OneTimeWorkRequestBuilder<SemanticInitializerWorker>()
                .setInitialDelay(INITIALIZATION_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()

        // REPLACE policy ensures that semantic layer initialization is restarted
        // with the latest app process lifetime if a previous attempt was pending.
        WorkManager.getInstance(this).enqueueUniqueWork(
            SEMANTIC_INIT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            initRequest,
        )

        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy
                    .Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy
                    .Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    override fun onTerminate() {
        AppLogger.shutdown()
        super.onTerminate()
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    companion object {
        private const val SEMANTIC_INIT_WORK_NAME = "semantic_init"
        private const val INITIALIZATION_DELAY_SECONDS = 5L
    }
}
