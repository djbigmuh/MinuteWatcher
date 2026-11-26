package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.observeWithRetry
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

internal class SettingsChangeObserver(
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository,
    private val onSettingsChanged: suspend () -> Unit,
) {
    private val appSettingsJob = AtomicReference<Job?>(null)
    private val simSettingsJob = AtomicReference<Job?>(null)

    @Volatile
    private var lastAppSettings: AppSettings? = null

    fun start() {
        startAppSettingsObservation()
        startSimSettingsObservation()
    }

    private fun startAppSettingsObservation() {
        val current = appSettingsJob.get()
        if (current != null && current.isActive) return

        val newJob =
            scope.launch {
                appSettingsRepository
                    .observeAppSettings()
                    .observeWithRetry(
                        label = "SettingsChangeObserver: AppSettings",
                        logThreshold = LOG_THRESHOLD,
                        onPersistentFailure = { e ->
                            AppLogger.w("SettingsChangeObserver: AppSettings observation failed persistently", e)
                        },
                        onRecovered = {
                            AppLogger.d("SettingsChangeObserver: AppSettings observation recovered")
                        },
                        onSourceCompleted = {},
                    ).collect { settings ->
                        val previous = lastAppSettings
                        lastAppSettings = settings
                        if (previous != null && previous != settings) {
                            AppLogger.d("UpdateCoordinator: AppSettings changed, updating widget")
                            onSettingsChanged()
                        }
                    }
            }

        if (!appSettingsJob.compareAndSet(current, newJob)) {
            newJob.cancel()
        }
    }

    private fun startSimSettingsObservation() {
        val current = simSettingsJob.get()
        if (current != null && current.isActive) return

        val newJob =
            scope.launch {
                simWidgetVisibilityRepository
                    .observeSimSettings()
                    .observeWithRetry(
                        label = "SettingsChangeObserver: SimSettings",
                        logThreshold = LOG_THRESHOLD,
                        onPersistentFailure = { e ->
                            AppLogger.w("SettingsChangeObserver: SimSettings observation failed persistently", e)
                        },
                        onRecovered = {
                            AppLogger.d("SettingsChangeObserver: SimSettings observation recovered")
                        },
                        onSourceCompleted = {},
                    ).collect {
                        AppLogger.d("UpdateCoordinator: SimSettings changed, updating widget")
                        onSettingsChanged()
                    }
            }

        if (!simSettingsJob.compareAndSet(current, newJob)) {
            newJob.cancel()
        }
    }

    fun restart() {
        cancelJobs()
        lastAppSettings = null
        start()
    }

    fun cancelJobs() {
        appSettingsJob.getAndSet(null)?.cancel()
        simSettingsJob.getAndSet(null)?.cancel()
    }

    companion object {
        private const val LOG_THRESHOLD = 3
    }
}
