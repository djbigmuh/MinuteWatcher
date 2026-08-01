package com.example.minutewatcher

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltAndroidApp
class MinuteWatcherApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var searchManager: SemanticSearchManager

    @Inject lateinit var shortcutManager: ShortcutManager

    @Inject lateinit var usageStateRepository: UsageStateRepository

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)

        // Defer semantic layer initialization to improve cold start performance
        applicationScope.launch {
            delay(5.seconds) // 5 seconds grace period for UI and primary setup

            val snapshotFlow = usageStateRepository.observeSnapshot()

            try {
                searchManager.initialize(snapshotFlow)
            } catch (e: Exception) {
                AppLogger.e("Failed to initialize AppSearch", e)
                searchManager.setError(e.message)
            }

            try {
                shortcutManager.initialize(snapshotFlow)
            } catch (e: Exception) {
                AppLogger.e("Failed to initialize shortcuts", e)
                shortcutManager.setError(e.message)
            }
        }

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

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
