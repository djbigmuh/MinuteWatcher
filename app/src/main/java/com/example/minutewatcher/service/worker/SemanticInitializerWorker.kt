package com.example.minutewatcher.service.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppFunctionAvailability
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Worker responsible for initializing semantic services (AppSearch and Shortcuts).
 * Using WorkManager ensures that initialization is attempted reliably even if the
 * initial application process is short-lived.
 *
 * Contract: Upon unsuccessful initialization, work is queued for retry; upon lack of system support,
 * it completes successfully without retries.
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
        private val appSettingsRepository: AppSettingsRepository,
        private val appFunctionAvailability: AppFunctionAvailability,
    ) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            executeSemanticInit()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("SemanticInitializerWorker: unexpected error in doWork", e)
            Result.failure()
        }

    private suspend fun executeSemanticInit(): Result {
            AppLogger.d("SemanticInitializerWorker: Starting initialization")

            try {
                val settings =
                    appSettingsRepository
                        .getAppSettings()
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                if (settings != null) {
                    appFunctionAvailability.setAssistantAccessEnabled(settings.assistantAccessEnabled)
                }
            } catch (e: Exception) {
                AppLogger.w("SemanticInitializerWorker: Failed to synchronize AppFunction availability", e)
            }

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
        val searchResult = indexInitialSnapshotSafely(initialSnapshot)
        val shortcutSuccess = updateShortcutsSyncSafely(initialSnapshot)

        val searchStatus = runCatching { searchManager.status.value }.getOrNull()
        val shortcutsStatus = runCatching { shortcutManager.status.value }.getOrNull()

            if (searchResult !is SemanticSearchManager.IndexResult.Success) {
                AppLogger.w("SemanticInitializerWorker: initial AppSearch indexing failed: $searchResult")
            }
            if (!shortcutSuccess) {
                AppLogger.w("SemanticInitializerWorker: initial shortcut update failed")
            }

        return evaluateResult(searchResult, shortcutSuccess, searchStatus, shortcutsStatus)
    }

    private suspend fun indexInitialSnapshotSafely(snapshot: GlobalSnapshot): SemanticSearchManager.IndexResult =
        try {
            searchManager.indexInitialSnapshot(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("SemanticInitializerWorker: initial AppSearch indexing failed with exception", e)
            SemanticSearchManager.IndexResult.ActualFailure()
        }

    private suspend fun updateShortcutsSyncSafely(snapshot: GlobalSnapshot): Boolean =
        try {
            shortcutManager.updateShortcutsSync(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("SemanticInitializerWorker: initial shortcut update failed with exception", e)
            false
        }

    private fun evaluateResult(
        searchResult: SemanticSearchManager.IndexResult,
        shortcutSuccess: Boolean,
        searchStatus: TrackingStatus?,
        shortcutsStatus: TrackingStatus?,
    ): Result {
        val appSearchNotSupported =
            (searchStatus as? TrackingStatus.Error)?.type == TrackingStatus.ErrorType.NOT_SUPPORTED
        val shortcutsNotSupported =
            (shortcutsStatus as? TrackingStatus.Error)?.type == TrackingStatus.ErrorType.NOT_SUPPORTED

        val isSearchSuccess =
            searchResult is SemanticSearchManager.IndexResult.Success ||
                (searchResult is SemanticSearchManager.IndexResult.ActualFailure && appSearchNotSupported) ||
                (searchResult is SemanticSearchManager.IndexResult.MutexContention && appSearchNotSupported)

        val isShortcutSuccess = shortcutSuccess || shortcutsNotSupported

        return when {
            isSearchSuccess && isShortcutSuccess -> Result.success()
            else -> evaluateFailureResult(searchResult, shortcutSuccess, appSearchNotSupported)
        }
    }

    private fun evaluateFailureResult(
        searchResult: SemanticSearchManager.IndexResult,
        shortcutSuccess: Boolean,
        appSearchNotSupported: Boolean,
    ): Result {
        val isMutexContention =
            searchResult is SemanticSearchManager.IndexResult.MutexContention && !appSearchNotSupported
        val isShortcutSuccess = shortcutSuccess

        return when {
            isMutexContention && isShortcutSuccess ->
                if (runAttemptCount < MAX_SEMANTIC_INIT_ATTEMPTS) Result.retry() else Result.success()

            else ->
                if (runAttemptCount < MAX_SEMANTIC_INIT_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        /**
         * Maximum number of execution attempts for semantic initialization before entering terminal state.
         * Retries are executed by WorkManager's standard mechanism with exponential backoff.
         */
        private const val MAX_SEMANTIC_INIT_ATTEMPTS = 3
        }
    }
