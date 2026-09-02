package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.exceptions.AppSearchException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        @com.example.minutewatcher.core.common.di.IoDispatcher
        private val ioDispatcher: CoroutineDispatcher,
        private val timeProvider: TimeProvider,
    ) : TrackingStatusProvider {
        override val key: String = "appsearch"

    @Volatile
        private var lastSnapshotHash: Int? = null

    @Volatile
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

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val initMutex = Mutex()
        private val indexMutex = Mutex()

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

    private constructor(
        context: Context,
        scope: CoroutineScope,
        appSearchWrapper: AppSearchWrapper,
        ioDispatcher: CoroutineDispatcher,
        dataStore: DataStore<Preferences>,
        timeProvider: TimeProvider,
        errorHandler: AppSearchErrorHandler,
    ) : this(
            sessionManager =
                AppSearchSessionManager(
                    context = context,
                    appSearchWrapper = appSearchWrapper,
                    errorHandler = errorHandler,
                ),
            indexer =
                SnapshotIndexer(
                    appSearchWrapper = appSearchWrapper,
                    errorHandler = errorHandler,
                    dataStore = dataStore,
                ),
        errorHandler = errorHandler,
            scope = scope,
            ioDispatcher = ioDispatcher,
        timeProvider = timeProvider,
        )

        override suspend fun recover() {
            initMutex.withLock {
                errorHandler.resetFailures()
                indexMutex.withLock {
                    lastSnapshotHash = null
                    lastSnapshotVersion = null
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
                indexMutex.withLock {
                    lastSnapshotHash = null
                    lastSnapshotVersion = null
                    try {
                        indexer.cleanupStalePackageTypes(sessionManager) { newStatus ->
                            _status.value = newStatus
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("SemanticSearchManager: stale package types cleanup failed during init", e)
                    }
                }
                setupObservation(snapshotFlow)
            }
        }

        @OptIn(FlowPreview::class)
        private suspend fun setupObservation(snapshotFlow: StateFlow<GlobalSnapshot>) {
            val oldJob = job
            if (oldJob?.isActive == true && (lastSnapshotFlow === snapshotFlow)) return

            oldJob?.cancel()
            try {
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    job?.join()
                } ?: run {
                    AppLogger.w("SemanticSearchManager: previous observation job did not terminate in time")
                    job?.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("SemanticSearchManager: failed to join previous observation job during setupObservation", e)
            }

            lastSnapshotFlow = snapshotFlow
            job =
                scope.launch {
                    snapshotFlow
                        .debounce { if (it.isManualUpdate) 0L else Constants.Semantic.THROTTLE_MS }
                        .collect { snapshot ->
                            ensureActive()
                            _status.value = TrackingStatus.Indexing
                            val result =
                                try {
                                    indexSnapshot(snapshot)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLogger.e("SemanticSearchManager: unexpected error during indexing", e)
                                    IndexResult.ActualFailure(e.message)
                                }

                            when (result) {
                                IndexResult.Success -> {
                                    errorHandler.resetFailures()
                                    // Status already set to Ready inside indexSnapshot
                                }

                                is IndexResult.ActualFailure -> {
                                    // Status already set to Error inside indexSnapshot
                                }

                                IndexResult.MutexTimeout -> {
                                    AppLogger.d(
                                        "SemanticSearchManager: skipping snapshot due to mutex timeout (concurrency)",
                                    )
                                    // If we were Indexing, go back to Idle or maintain current status
                                    if (_status.value is TrackingStatus.Indexing) {
                                        _status.value = TrackingStatus.Idle
                                    }
                                }
                            }
                        }
                }
        }

        fun setError(message: String?) {
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
        }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    suspend fun indexSnapshot(snapshot: GlobalSnapshot): IndexResult {
        val mutexAcquired =
            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                indexMutex.lock()
                true
            } ?: false

        if (!mutexAcquired) {
            AppLogger.w("SemanticSearchManager: indexMutex acquisition timed out (concurrency)")
            return IndexResult.MutexTimeout
        }

        return try {
            val result =
                withTimeoutOrNull(Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS.milliseconds) {
                    performIndexSnapshotLocked(snapshot)
                } ?: IndexResult.ActualFailure("Indexing timed out")

            when (result) {
                is IndexResult.ActualFailure -> {
                    _status.update { currentState ->
                        errorHandler.handleIndexFailure(currentState, result.message)
                    }
                }

                IndexResult.Success -> {
                    errorHandler.resetFailures()
                    _status.value = TrackingStatus.Ready(timeProvider.now())
                }

                IndexResult.MutexTimeout -> {
                    // Handled by return
                }
            }
            result
        } finally {
            indexMutex.unlock()
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun performIndexSnapshotLocked(snapshot: GlobalSnapshot): IndexResult {
        val hasActiveSims = snapshot.simSnapshots.any { it.isActive }
        val currentHash = if (hasActiveSims) SnapshotUtils.calculateContentHash(snapshot) else null

        if (hasActiveSims &&
            (currentHash == lastSnapshotHash) &&
            (snapshot.version == lastSnapshotVersion)
        ) {
            return IndexResult.Success
        }

        if (!sessionManager.ensureSession({ _status.value = it })) {
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
        return if (sessionManager.ensureSession({ _status.value = it }, forceOverride = true)) {
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
                if (!sessionManager.ensureSession({ _status.value = it })) {
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

    suspend fun query(
        queryExpression: String,
        limit: Int = 10,
    ): Result<List<androidx.appsearch.app.SearchResult>> =
        withContext(ioDispatcher) {
            if (!sessionManager.ensureSession({ _status.value = it })) {
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
                    androidx.appsearch.app.SearchSpec
                        .Builder()
                        .setSnippetCount(limit)
                        .addFilterNamespaces(AppSearchConstants.NAMESPACE)
                        .build()
                Result.success(currentSession.query(queryExpression, spec))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("AppSearch query failed", e)
                Result.failure(e)
            }
        }
}
