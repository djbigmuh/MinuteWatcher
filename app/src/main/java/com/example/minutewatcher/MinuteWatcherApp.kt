package com.example.minutewatcher

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import com.example.minutewatcher.BuildConfig
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

        // Defer semantic layer initialization to improve cold start performance
        applicationScope.launch {
            delay(5.seconds) // 5 seconds grace period for UI and primary setup
            searchManager.initialize(usageStateRepository.observeSnapshot())
            shortcutManager.initialize(usageStateRepository.observeSnapshot())
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

