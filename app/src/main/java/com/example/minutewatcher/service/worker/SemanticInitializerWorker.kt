package com.example.minutewatcher.service.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.TrackingStatus
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

            // Perform initial indexing and shortcut update synchronously to catch immediate failures
            val searchSuccess = searchManager.indexSnapshot(initialSnapshot)
            val shortcutSuccess = shortcutManager.updateShortcutsSync(initialSnapshot)

            // Start background observers for continuous updates
            searchManager.initialize(snapshotFlow)
            shortcutManager.initialize(snapshotFlow)

            if (!searchSuccess) {
                val status = searchManager.status.value
                if (status is TrackingStatus.Error && status.type == TrackingStatus.ErrorType.DATABASE_ERROR) {
                    AppLogger.w("SemanticInitializerWorker: AppSearch database error. Manual retry required via UI.")
                    return Result.success()
                }
            }

            return if (searchSuccess && shortcutSuccess) {
                Result.success()
            } else {
                // For non-fatal errors, we return success to avoid infinite retries
                // but the error state is still visible in the UI for manual retry.
                Result.success()
            }
        }
    }
