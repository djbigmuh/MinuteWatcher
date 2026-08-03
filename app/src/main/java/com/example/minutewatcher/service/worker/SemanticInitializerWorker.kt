package com.example.minutewatcher.service.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Worker responsible for initializing semantic services (AppSearch and Shortcuts).
 * Using WorkManager ensures that initialization is attempted reliably even if the
 * initial application process is short-lived.
 */
@HiltWorker
class SemanticInitializerWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val searchManager: SemanticSearchManager,
        private val shortcutManager: ShortcutManager,
        private val usageStateRepository: UsageStateRepository,
    ) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppLogger.d("SemanticInitializerWorker: Starting initialization")
        val snapshotFlow = usageStateRepository.observeSnapshot()

        try {
            searchManager.initialize(snapshotFlow)
        } catch (e: Exception) {
            AppLogger.e("Failed to initialize AppSearch in Worker", e)
            searchManager.setError(e.message)
            // We don't necessarily want to retry the whole worker if one fails
            // but for fatal DB issues retry might help.
        }

        try {
            shortcutManager.initialize(snapshotFlow)
        } catch (e: Exception) {
            AppLogger.e("Failed to initialize shortcuts in Worker", e)
            shortcutManager.setError(e.message)
        }

        return Result.success()
    }
}
