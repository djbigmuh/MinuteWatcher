package com.example.minutewatcher.service.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

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

            // Start background observers for continuous updates.
            // These will eventually handle indexing when data arrives.
            searchManager.initialize(snapshotFlow)
            shortcutManager.initialize(snapshotFlow)

            // Wait for the first meaningful snapshot to perform synchronous initialization.
            val initialSnapshot =
                withTimeoutOrNull(Constants.Semantic.INITIAL_SNAPSHOT_TIMEOUT_MS.milliseconds) {
                    snapshotFlow.first { it.timestamp != Constants.Data.EMPTY_TIMESTAMP }
                }

            if (initialSnapshot == null) {
                AppLogger.w(
                    "SemanticInitializerWorker: no loaded snapshot within timeout, " +
                        "skipping synchronous indexing (observers remain active)",
                )
                return Result.success()
            }

            // Perform initial indexing and shortcut update synchronously to catch immediate failures.
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
