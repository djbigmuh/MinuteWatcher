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

        private var lastSnapshotHash: Int? = null
        private var lastSnapshotVersion: SchemaVersion? = null
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
            errorHandler.resetFailures()
            lastSnapshotFlow?.let { initialize(it) }
        }

        @OptIn(FlowPreview::class)
        suspend fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            initMutex.withLock {
                job?.cancel()
                job?.join()

                lastSnapshotHash = null
                lastSnapshotVersion = null
                indexer.cleanupStalePackageTypes(sessionManager, _status)
                setupObservation(snapshotFlow)
            }
        }

        @OptIn(FlowPreview::class)
        private fun setupObservation(snapshotFlow: StateFlow<GlobalSnapshot>) {
            lastSnapshotFlow = snapshotFlow
            job =
                scope.launch {
                    _status.value = TrackingStatus.Active
                    snapshotFlow
                        .debounce { if (it.isManualUpdate) 0L else Constants.Semantic.THROTTLE_MS }
                        .collect { snapshot ->
                            ensureActive()
                            if (errorHandler.consecutiveIndexFailures >=
                                AppSearchErrorHandler.MAX_CONSECUTIVE_INDEX_FAILURES
                            ) {
                                errorHandler.handleIndexFailure(_status)
                                return@collect
                            }
                            try {
                                val success = indexSnapshot(snapshot)
                                if (success) {
                                    errorHandler.resetFailures()
                                } else {
                                    errorHandler.handleIndexFailure(_status)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("SemanticSearchManager: unexpected error during indexing", e)
                                errorHandler.handleIndexFailure(_status)
                            }
                        }
                }
        }

        fun setError(message: String?) {
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
        }

        @Suppress("ReturnCount")
        suspend fun indexSnapshot(snapshot: GlobalSnapshot): Boolean =
            indexMutex.withLock {
                val hasActiveSims = snapshot.simSnapshots.any { it.isActive }
                val currentHash = if (hasActiveSims) SnapshotUtils.calculateContentHash(snapshot) else null

                if (hasActiveSims && (currentHash == lastSnapshotHash) && (snapshot.version == lastSnapshotVersion)) {
                    return@withLock true
                }
                if (!hasActiveSims && (lastSnapshotHash == null)) return@withLock true

                if (sessionManager.ensureSession(_status)) {
                    return@withLock performIndexingWithRetry(snapshot, currentHash)
                }
                resetLastSnapshotState()
                false
            }

        private suspend fun performIndexingWithRetry(
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): Boolean =
            try {
                executeIndexing(snapshot, currentHash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppSearchException) {
                handleIndexingAppSearchException(e, snapshot, currentHash)
            } catch (e: Exception) {
                AppLogger.e("AppSearch non-AppSearch error during indexing", e)
                resetLastSnapshotState()
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                false
            }

        private suspend fun handleIndexingAppSearchException(
            e: AppSearchException,
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): Boolean {
            if (e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) {
                AppLogger.w("AppSearch schema mismatch, retrying with force override")
                sessionManager.closeSession()
                if (sessionManager.ensureSession(_status, forceOverride = true)) {
                    return try {
                        executeIndexing(snapshot, currentHash)
                    } catch (retryException: CancellationException) {
                        throw retryException
                    } catch (retryException: Exception) {
                        AppLogger.e("AppSearch indexing failed again after retry", retryException)
                        resetLastSnapshotState()
                        _status.value =
                            TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, retryException.message)
                        false
                    }
                }
            } else {
                AppLogger.e("AppSearch indexing failed", e)
                resetLastSnapshotState()
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
            }
            return false
        }

        private fun resetLastSnapshotState() {
            lastSnapshotHash = null
            lastSnapshotVersion = null
        }

        private suspend fun executeIndexing(
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): Boolean =
            withContext(ioDispatcher) {
                val currentSession =
                    sessionManager.session ?: run {
                        AppLogger.e("AppSearch session is null in executeIndexing, skipping")
                        return@withContext false
                    }

                val result: Boolean? =
                    try {
                        withTimeoutOrNull(Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS.milliseconds) {
                            indexer.performIndexUpdate(currentSession, snapshot)
                            lastSnapshotHash = currentHash
                            lastSnapshotVersion = snapshot.version
                            _status.value = TrackingStatus.Active
                            true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AppSearchException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("AppSearch indexing error", e)
                        resetLastSnapshotState()
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                        false
                    }

                if (result == null) {
                    AppLogger.e("AppSearch indexing timed out")
                    resetLastSnapshotState()
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Indexing timeout")
                    return@withContext false
                }
                result
            }
    }
