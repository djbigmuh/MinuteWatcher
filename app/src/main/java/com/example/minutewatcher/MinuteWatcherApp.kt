package com.example.minutewatcher

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MinuteWatcherApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var searchManager: com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
    @Inject lateinit var shortcutManager: com.example.minutewatcher.feature.semantic.ShortcutManager
    @Inject lateinit var usageStateRepository: com.example.minutewatcher.core.domain.UsageStateRepository

    override fun onCreate() {
        super.onCreate()
        
        searchManager.initialize(usageStateRepository.observeSnapshot())
        shortcutManager.initialize(usageStateRepository.observeSnapshot())

        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onTerminate() {
        searchManager.close()
        shortcutManager.close()
        super.onTerminate()
    }
}
