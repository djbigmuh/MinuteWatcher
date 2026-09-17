package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.SearchResult
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.exceptions.AppSearchException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.TrackingStatusMachine
import com.example.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * SemanticSearchManager manages AppSearch indexing for usage data.
 *
 * This class acts as an orchestrator, delegating specific responsibilities to:
 * - [AppSearchSessionManager] for session lifecycle management.
 * - [SnapshotIndexer] for document indexing and stale data cleanup.
 * - [AppSearchErrorHandler] for error classification and status management.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
@Suppress("TooManyFunctions")
open class SemanticSearchManager
    @Inject
    constructor(
        private val sessionManager: AppSearchSessionManager,
        private val indexer: SnapshotIndexer,
        private val errorHandler: AppSearchErrorHandler,
        @ApplicationScope private val scope: CoroutineScope,
        @IoDispatcher
        private val ioDispatcher: CoroutineDispatcher,
        private val timeProvider: TimeProvider,
    ) : TrackingStatusProvider {
        override val key: TrackingKey = TrackingKey.APPSEARCH

        private val statusMachine = TrackingStatusMachine()
        override val status: StateFlow<TrackingStatus> = statusMachine.status

        /**
         * Updates the tracker status using canonical prioritized rules.
         */
        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            statusMachine.transition(newStatus, force)
        }

        private var lastSnapshotHash: Int? = null
        private var lastSnapshotVersion: SchemaVersion? = null

        /** Result of an indexing attempt. */
        sealed interface IndexResult {
            data object Success : IndexResult

            data class ActualFailure(
                val message: String? = null,
            ) : IndexResult

            data object MutexTimeout : IndexResult
        }

        private var job: Job? = null
        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null

        private val initMutex = Mutex()
        private val indexMutex = Mutex()
        private val consecutiveMutexTimeouts = AtomicInteger(0)

        @VisibleForTesting
        constructor(
            context: Context,
            scope: CoroutineScope,
            appSearchWrapper: AppSearchWrapper,
            ioDispatcher: CoroutineDispatcher,
            dataStore: DataStore<Preferences>,
            timeProvider: TimeProvider,
        ) : this(
            sessionManager =
                AppSearchSessionManager(
                    context = context,
                    appSearchWrapper = appSearchWrapper,
                    errorHandler = AppSearchErrorHandler(timeProvider),
                ),
            indexer =
                SnapshotIndexer(
                    appSearchWrapper = appSearchWrapper,
                    errorHandler = AppSearchErrorHandler(timeProvider),
                    dataStore = dataStore,
                ),
            errorHandler = AppSearchErrorHandler(timeProvider),
            scope = scope,
            ioDispatcher = ioDispatcher,
            timeProvider = timeProvider,
        )

        override suspend fun recover() {
            initMutex.withLock {
                errorHandler.resetFailures()

                val acquired =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        indexMutex.withLock {
                            lastSnapshotHash = null
                            lastSnapshotVersion = null
                            consecutiveMutexTimeouts.set(0)
                        }
                        true
                    } ?: false

                if (!acquired) {
                    AppLogger.e("SemanticSearchManager: recovery blocked by indexing mutex")
                    transitionStatus(
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            "Recovery blocked: indexing in progress or stuck",
                        ),
                    )
                    return
                }

                val flow =
                    lastSnapshotFlow ?: run {
                        AppLogger.w("SemanticSearchManager: recovery skipped, lastSnapshotFlow is null")
                        return
                    }
                setupObservation(flow)
            }
        }

        @OptIn(FlowPreview::class)
        suspend fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            initMutex.withLock {
                val acquired =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        indexMutex.withLock {
                            lastSnapshotHash = null
                            lastSnapshotVersion = null
                            try {
                                indexer.cleanupStalePackageTypes(sessionManager) { newStatus ->
                                    transitionStatus(newStatus)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("SemanticSearchManager: stale package types cleanup failed during init", e)
                            }
                        }
                        true
                    } ?: false

                if (!acquired) {
                    AppLogger.w(
                        "SemanticSearchManager: initialization stale data cleanup blocked, proceeding with observation",
                    )
                }

                setupObservation(snapshotFlow)
            }
        }

        @OptIn(FlowPreview::class)
        private suspend fun setupObservation(snapshotFlow: StateFlow<GlobalSnapshot>) {
            if (isCurrentObservationActive(snapshotFlow)) return

            cancelAndJoinPreviousJob()

            lastSnapshotFlow = snapshotFlow
            job =
                scope.launch {
                    snapshotFlow
                        .debounce { if (it.isManualUpdate) 0L else Constants.Semantic.THROTTLE_MS }
                        .collect { snapshot ->
                            processSnapshotInternal(snapshot)
                        }
                }
        }

        private fun isCurrentObservationActive(snapshotFlow: StateFlow<GlobalSnapshot>): Boolean =
            job?.isActive == true && (lastSnapshotFlow === snapshotFlow)

        private suspend fun cancelAndJoinPreviousJob() {
            val currentJob = job ?: return
            currentJob.cancel()
            try {
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    currentJob.join()
                } ?: run {
                    AppLogger.w("SemanticSearchManager: previous observation job did not terminate in time")
                    currentJob.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(
                    "SemanticSearchManager: failed to join previous observation job during setupObservation",
                    e,
                )
            }
        }

        private suspend fun processSnapshotInternal(snapshot: GlobalSnapshot) {
            currentCoroutineContext().ensureActive()
            try {
                // Initializing/Indexing status removed per TZ-AUDIT-14 as it was dead and unused.
                val result =
                    try {
                        indexSnapshot(snapshot)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("SemanticSearchManager: unexpected error during indexing", e)
                        IndexResult.ActualFailure(e.message)
                    }

                handleIndexResult(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("SemanticSearchManager: error processing snapshot in collect", e)
                transitionStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message))
            }
        }

        private fun handleIndexResult(result: IndexResult) {
            when (result) {
                IndexResult.Success -> {
                    errorHandler.resetFailures()
                }

                IndexResult.MutexTimeout -> {
                    handleMutexTimeout()
                }

                else -> {
                    // Actual failure is already handled inside indexSnapshot update logic.
                }
            }
        }

        private fun handleMutexTimeout() {
            val count = consecutiveMutexTimeouts.get()
            // Log as debug for transient timeouts, warning if it persists.
            if (count < MAX_MUTEX_TIMEOUT_RETRIES) {
                AppLogger.d(
                    "SemanticSearchManager: indexing skipped due to mutex timeout (cycle $count)",
                )
            } else {
                AppLogger.w(
                    "SemanticSearchManager: indexing consistently skipped due to mutex timeout " +
                        "($count consecutive)",
                )
            }
        }

        @Suppress("ReturnCount", "TooGenericExceptionCaught")
        internal suspend fun indexSnapshot(snapshot: GlobalSnapshot): IndexResult {
            if (!indexMutex.tryLock()) {
                consecutiveMutexTimeouts.incrementAndGet()

                if (consecutiveMutexTimeouts.get() >= 2) {
                    transitionStatus(
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            "Indexing mutex stuck",
                        ),
                    )
                }
                // We do not delay here to avoid blocking the observation collect path.
                // Any further data changes or manual actions will trigger a new indexing attempt.
                return IndexResult.MutexTimeout
            }

            try {
                consecutiveMutexTimeouts.set(0)

                val result =
                    withTimeoutOrNull(Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS.milliseconds) {
                        performIndexSnapshotLocked(snapshot)
                    } ?: IndexResult.ActualFailure("Indexing timed out")

                when (result) {
                    is IndexResult.ActualFailure -> {
                        val nextStatus = errorHandler.handleIndexFailure(statusMachine.current, result.message)
                        transitionStatus(nextStatus)
                    }

                    IndexResult.Success -> {
                        errorHandler.resetFailures()
                        transitionStatus(TrackingStatus.Ready(timeProvider.now()), force = true)
                    }

                    IndexResult.MutexTimeout -> {
                        // Should not happen in performIndexSnapshotLocked
                    }
                }
                return result
            } finally {
                indexMutex.unlock()
            }
        }

        @Suppress("ReturnCount", "TooGenericExceptionCaught")
        private suspend fun performIndexSnapshotLocked(snapshot: GlobalSnapshot): IndexResult {
            val hasSims = snapshot.simSnapshots.isNotEmpty()
            val currentHash = if (hasSims) SnapshotUtils.calculateContentHash(snapshot) else null

            if (hasSims &&
                (currentHash == lastSnapshotHash) &&
                (snapshot.version == lastSnapshotVersion)
            ) {
                return IndexResult.Success
            }

            if (!sessionManager.ensureSession({ transitionStatus(it) })) {
                resetLastSnapshotState()
                return IndexResult.ActualFailure("Failed to ensure AppSearch session")
            }

            return try {
                executeIndexingInternal(snapshot, currentHash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppSearchException) {
                if (e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) {
                    tryHandleSchemaMismatch(snapshot, currentHash)
                } else {
                    AppLogger.e("AppSearch indexing failed", e)
                    resetLastSnapshotState()
                    IndexResult.ActualFailure(e.message)
                }
            } catch (e: Exception) {
                AppLogger.e("AppSearch non-AppSearch error during indexing", e)
                resetLastSnapshotState()
                IndexResult.ActualFailure(e.message)
            }
        }

        private suspend fun tryHandleSchemaMismatch(
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): IndexResult {
            AppLogger.w("AppSearch schema mismatch, retrying with force override")
            sessionManager.closeSession()
            return if (sessionManager.ensureSession({ transitionStatus(it) }, forceOverride = true)) {
                try {
                    executeIndexingInternal(snapshot, currentHash)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AppSearchException) {
                    AppLogger.e("AppSearch indexing failed after schema reset (code=${e.resultCode})", e)
                    resetLastSnapshotState()
                    IndexResult.ActualFailure(e.message)
                } catch (e: Exception) {
                    AppLogger.e("Unexpected error after schema reset", e)
                    resetLastSnapshotState()
                    IndexResult.ActualFailure(e.message)
                }
            } else {
                IndexResult.ActualFailure("Failed to ensure AppSearch session after schema mismatch")
            }
        }

        private suspend fun executeIndexingInternal(
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): IndexResult =
            withContext(ioDispatcher) {
                // Ensure the session is valid and active immediately before usage.
                // sessionManager.ensureSession is synchronized internally.
                if (!sessionManager.ensureSession({ transitionStatus(it) })) {
                    AppLogger.e("AppSearch session could not be ensured in executeIndexingInternal")
                    return@withContext IndexResult.ActualFailure("AppSearch session could not be ensured")
                }

                val currentSession =
                    sessionManager.session ?: run {
                        AppLogger.e("AppSearch session is null in executeIndexingInternal, skipping")
                        return@withContext IndexResult.ActualFailure("AppSearch session is null")
                    }

                try {
                    indexer.performIndexUpdate(currentSession, snapshot)
                    lastSnapshotHash = currentHash
                    lastSnapshotVersion = snapshot.version
                    IndexResult.Success
                } catch (e: CancellationException) {
                    resetLastSnapshotState()
                    throw e
                } catch (e: AppSearchException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("AppSearch indexing error", e)
                    resetLastSnapshotState()
                    IndexResult.ActualFailure(e.message)
                }
            }

        private fun resetLastSnapshotState() {
            lastSnapshotHash = null
            lastSnapshotVersion = null
        }

        /**
         * Queries the AppSearch index.
         *
         * @param queryExpression The search query.
         * @param limit Limits the number of returned SearchResults; page control via platform API is not available.
         * @return A Result containing a list of search results.
         */
        suspend fun query(
            queryExpression: String,
            limit: Int = 10,
        ): Result<List<SearchResult>> =
            withContext(ioDispatcher) {
                if (!sessionManager.ensureSession({ transitionStatus(it) })) {
                    return@withContext Result.failure(
                        AppSearchException(
                            AppSearchResult.RESULT_INTERNAL_ERROR,
                            "Session not ready",
                        ),
                    )
                }

                val currentSession =
                    sessionManager.session ?: return@withContext Result.failure(
                        AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR, "Session is null"),
                    )

                try {
                    val spec =
                        SearchSpec
                            .Builder()
                            .setSnippetCount(SNIPPETS_PER_DOCUMENT)
                            .addFilterNamespaces(AppSearchConstants.NAMESPACE)
                            .build()
                    val results = currentSession.query(queryExpression, spec)
                    Result.success(results.take(limit))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("AppSearch query failed", e)
                    Result.failure(e)
                }
            }

        /**
         * Performs an initial indexing of the given snapshot.
         *
         * This is a public wrapper for [indexSnapshot] intended for use during
         * initialization (e.g. from WorkManager) where synchronous feedback is needed.
         */
        suspend fun indexInitialSnapshot(snapshot: GlobalSnapshot): IndexResult = indexSnapshot(snapshot)

        private companion object {
            private const val SNIPPETS_PER_DOCUMENT = 2

            /**
             * Maximum consecutive mutex timeouts before escalating to warning in logs.
             * This threshold prevents log spamming for transient contention while ensuring
             * persistent issues are visible.
             */
            private const val MAX_MUTEX_TIMEOUT_RETRIES = 3
        }
    }
