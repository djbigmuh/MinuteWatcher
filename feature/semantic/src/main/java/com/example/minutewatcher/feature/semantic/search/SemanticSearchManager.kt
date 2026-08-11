package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.semantic.SemanticMapper
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.SnapshotUtils
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages AppSearch indexing for usage data.
 * Ensures that data is searchable via global system search.
 */
@Singleton
@Suppress("TooManyFunctions")
class SemanticSearchManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val appSearchWrapper: AppSearchWrapper,
        @com.example.minutewatcher.core.common.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : TrackingStatusProvider {
        override val key: String = "appsearch"

        private val sessionMutex = Mutex()
        private var session: AppSearchSessionWrapper? = null
        private var lastSnapshotHash: Int? = null
        private var job: Job? = null
        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        override suspend fun recover() {
            lastSnapshotFlow?.let { initialize(it) }
        }

        fun setError(message: String?) {
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
        }

        @OptIn(FlowPreview::class)
        fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            lastSnapshotFlow = snapshotFlow
            job?.cancel()
            job =
                scope.launch {
                    snapshotFlow
                        .debounce { snapshot ->
                            if (snapshot.isManualUpdate) 0L else Constants.Semantic.THROTTLE_MS
                        }.collect { snapshot ->
                            ensureActive()
                            try {
                                indexSnapshot(snapshot)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("SemanticSearchManager: unexpected error during indexing, will continue", e)
                            }
                        }
                }
        }

        private suspend fun ensureSession(forceOverride: Boolean = false): Boolean {
            val s = session
            if ((s != null) && (!s.isClosed) && (!forceOverride)) return true

            session?.close()
            session = null

            if (forceOverride) return tryOpenSession(forceOverride = true)

            if (tryOpenSession(false)) return true
            return tryOpenSession(true)
        }

        private suspend fun tryOpenSession(forceOverride: Boolean): Boolean {
            var newSession: AppSearchSessionWrapper? = null
            return try {
                newSession = appSearchWrapper.createSearchSession(context, DATABASE_NAME)
                newSession.setSchema(appSearchWrapper.createSchemaRequest(forceOverride))
                session = newSession
                _status.value = TrackingStatus.Active
                true
            } catch (e: AppSearchException) {
                newSession?.close()
                handleSchemaException(e, forceOverride)
            } catch (e: CancellationException) {
                newSession?.close()
                throw e
            } catch (e: Exception) {
                newSession?.close()
                handleInitializationError(e)
            }
        }

        private fun handleSchemaException(
            e: AppSearchException,
            forceOverride: Boolean,
        ): Boolean {
            if ((e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) && !forceOverride) {
                return false
            }
            AppLogger.e("AppSearch schema setup failed (force=$forceOverride, code=${e.resultCode})", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
            return false
        }

        private fun handleInitializationError(e: Exception): Boolean {
            val isUnsupported =
                (e is IllegalStateException) ||
                    (e.message?.contains("AppSearch", ignoreCase = true) == true) ||
                    (e.message?.contains("Service not found", ignoreCase = true) == true)

            return if (isUnsupported) {
                AppLogger.w("AppSearch is not supported on this device. Disabling indexing gracefully.")
                _status.value = TrackingStatus.Idle
                true
            } else {
                AppLogger.e("Fatal AppSearch initialization error", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                true
            }
        }

        suspend fun indexSnapshot(snapshot: GlobalSnapshot): Boolean {
            val hasActiveSims = snapshot.simSnapshots.any { it.isActive }
            val currentHash =
                if (hasActiveSims) SnapshotUtils.calculateContentHash(snapshot) else null
            return sessionMutex.withLock {
                if (hasActiveSims && (currentHash == lastSnapshotHash)) return@withLock true
                if (!hasActiveSims && (lastSnapshotHash == null)) return@withLock true

                var result = false
                if (ensureSession()) {
                    try {
                        result = executeIndexing(snapshot, currentHash)
                    } catch (e: AppSearchException) {
                        if (e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) {
                            AppLogger.w("AppSearch schema mismatch, retrying with force override")
                            session?.close()
                            session = null
                            if (ensureSession(forceOverride = true)) {
                                try {
                                    result = executeIndexing(snapshot, currentHash)
                                } catch (retryException: CancellationException) {
                                    throw retryException
                                } catch (retryException: Exception) {
                                    AppLogger.e("AppSearch indexing failed again after retry", retryException)
                                    lastSnapshotHash = null
                                    _status.value =
                                        TrackingStatus.Error(
                                            TrackingStatus.ErrorType.DATABASE_ERROR,
                                            retryException.message,
                                        )
                                }
                            }
                        } else {
                            AppLogger.e("AppSearch indexing failed", e)
                            lastSnapshotHash = null
                            _status.value =
                                TrackingStatus.Error(
                                    TrackingStatus.ErrorType.DATABASE_ERROR,
                                    e.message,
                                )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("AppSearch non-AppSearch error during indexing", e)
                        lastSnapshotHash = null
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    }
                }
                result
            }
        }

        private suspend fun executeIndexing(
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): Boolean =
            withContext(ioDispatcher) {
                try {
                    withTimeout(Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS.milliseconds) {
                        val documents = UsageDocument.fromGlobalSnapshot(snapshot)
                        performIndexUpdate(documents)
                        lastSnapshotHash = currentHash
                        _status.value = TrackingStatus.Active
                        true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AppSearchException) {
                    // Bubble up to indexSnapshot for retry
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("AppSearch indexing error", e)
                    lastSnapshotHash = null
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    false
                }
            }

        private suspend fun performIndexUpdate(documents: List<UsageDocument>) {
            val currentIds = documents.asSequence().map { it.id }.toSet()

            var putException: Exception? = null

            // Сначала добавляем/обновляем актуальные документы
            if (documents.isNotEmpty()) {
                try {
                    session?.putDocuments(appSearchWrapper.createPutRequest(documents))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Partial failure during putDocuments, will proceed with removal", e)
                    putException = e
                }
            }

            // Затем удаляем устаревшие
            val staleIds = mutableSetOf<String>()
            SimConstants.SUPPORTED_SLOT_INDICES.forEach { slot ->
                val possibleIds =
                    listOf(
                        SemanticMapper.getDocumentId(slot, PackageType.MINUTES),
                        SemanticMapper.getDocumentId(slot, PackageType.DATA),
                    )
                staleIds.addAll(possibleIds.filter { it !in currentIds })
            }

            var removeException: Exception? = null
            if (staleIds.isNotEmpty()) {
                try {
                    session?.removeByDocumentId(appSearchWrapper.createRemoveRequest(staleIds))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failure during removeByDocumentId", e)
                    removeException = e
                }
            }

            throwIfErrors(putException, removeException)
        }

        private fun throwIfErrors(
            putEx: Exception?,
            removeEx: Exception?,
        ) {
            if (putEx != null && removeEx != null) {
                error("put failed: ${putEx.message}; remove failed: ${removeEx.message}")
            }
            putEx?.let { throw it }
            removeEx?.let { throw it }
        }

        companion object {
            private const val DATABASE_NAME = "usage"
            const val NAMESPACE = "usage"
        }
    }
