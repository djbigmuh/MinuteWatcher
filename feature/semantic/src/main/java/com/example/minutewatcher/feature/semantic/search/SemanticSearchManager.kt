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
    ) : TrackingStatusProvider {
        override val key: String = "appsearch"

    @Volatile
        private var lastSnapshotHash: Int? = null

    @Volatile
        private var lastSnapshotVersion: SchemaVersion? = null

    /** Result of an indexing attempt. */
    sealed interface IndexResult {
        data object Success : IndexResult
        data object ActualFailure : IndexResult
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
                    errorHandler.resetFailures()
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
            if (oldJob?.isActive == true && lastSnapshotFlow === snapshotFlow) return

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
                    _status.value = TrackingStatus.Active
                    snapshotFlow
                        .debounce { if (it.isManualUpdate) 0L else Constants.Semantic.THROTTLE_MS }
                        .collect { snapshot ->
                            ensureActive()
                            val result =
                                try {
                                    indexSnapshot(snapshot)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLogger.e("SemanticSearchManager: unexpected error during indexing", e)
                                    IndexResult.ActualFailure
                                }

                            when (result) {
                                IndexResult.Success -> {
                                    errorHandler.resetFailures()
                                }

                                IndexResult.ActualFailure -> {
                                    _status.update { currentState ->
                                        errorHandler.handleIndexFailure(currentState)
                                    }
                                }

                                IndexResult.MutexTimeout -> {
                                    AppLogger.d("SemanticSearchManager: skipping snapshot due to mutex timeout (concurrency)")
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
            val success =
                withTimeoutOrNull(Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS.milliseconds) {
                    performIndexSnapshotLocked(snapshot)
                } ?: run {
                    AppLogger.e("SemanticSearchManager: indexing operation timed out")
                    false
                }
            if (success) IndexResult.Success else IndexResult.ActualFailure
        } finally {
            indexMutex.unlock()
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun performIndexSnapshotLocked(snapshot: GlobalSnapshot): Boolean {
        val hasActiveSims = snapshot.simSnapshots.any { it.isActive }
        val currentHash = if (hasActiveSims) SnapshotUtils.calculateContentHash(snapshot) else null

        if (hasActiveSims &&
            (currentHash == lastSnapshotHash) &&
            (snapshot.version == lastSnapshotVersion)
        ) {
            return true
        }

        if (!sessionManager.ensureSession({ _status.value = it })) {
            resetLastSnapshotState()
            return false
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
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
                    false
            }
        } catch (e: Exception) {
            AppLogger.e("AppSearch non-AppSearch error during indexing", e)
            resetLastSnapshotState()
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            false
        }
    }

    private suspend fun tryHandleSchemaMismatch(
        snapshot: GlobalSnapshot,
        currentHash: Int?,
    ): Boolean {
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
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
                false
            } catch (e: Exception) {
                AppLogger.e("Unexpected error after schema reset", e)
                resetLastSnapshotState()
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                false
            }
        } else {
            false
        }
    }

    private suspend fun executeIndexingInternal(
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): Boolean =
            withContext(ioDispatcher) {
                // Ensure the session is valid and active immediately before usage.
                // sessionManager.ensureSession is synchronized internally.
                if (!sessionManager.ensureSession({ _status.value = it })) {
                    AppLogger.e("AppSearch session could not be ensured in executeIndexingInternal")
                    return@withContext false
                }

                val currentSession =
                    sessionManager.session ?: run {
                        AppLogger.e("AppSearch session is null in executeIndexingInternal, skipping")
                        return@withContext false
                    }

                try {
                    indexer.performIndexUpdate(currentSession, snapshot)
                    lastSnapshotHash = currentHash
                    lastSnapshotVersion = snapshot.version
                    _status.value = TrackingStatus.Active
                    true
                } catch (e: CancellationException) {
                    resetLastSnapshotState()
                    throw e
                } catch (e: AppSearchException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("AppSearch indexing error", e)
                    resetLastSnapshotState()
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    false
                }
            }

    private fun resetLastSnapshotState() {
        lastSnapshotHash = null
        lastSnapshotVersion = null
    }
    }
