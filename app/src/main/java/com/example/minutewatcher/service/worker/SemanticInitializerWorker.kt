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
import kotlinx.coroutines.flow.first

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
            val initialSnapshot = snapshotFlow.first()

            // Start background observers for continuous updates
            searchManager.initialize(snapshotFlow)
            shortcutManager.initialize(snapshotFlow)

            // Perform initial indexing and shortcut update synchronously to catch immediate failures.
            // We log the outcome but return success to avoid infinite WorkManager retries,
            // as error state is visible in the UI and can be retried manually.
            val searchResult = searchManager.indexInitialSnapshot(initialSnapshot)
            val shortcutSuccess = shortcutManager.updateShortcutsSync(initialSnapshot)

            if (searchResult !is SemanticSearchManager.IndexResult.Success) {
                AppLogger.w("SemanticInitializerWorker: initial AppSearch indexing failed: $searchResult")
            }
            if (!shortcutSuccess) {
                AppLogger.w("SemanticInitializerWorker: initial shortcut update failed")
            }

            return Result.success()
        }
}
