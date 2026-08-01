package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.SnapshotUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
open class SemanticSearchManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val scope: CoroutineScope,
        private val appSearchWrapper: AppSearchWrapper,
        private val timeProvider: TimeProvider,
        @com.example.minutewatcher.core.common.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : TrackingStatusProvider {
        override val key: String = "appsearch"

        private val sessionMutex = Mutex()
        private var session: AppSearchSessionWrapper? = null
        private var lastSnapshotHash: Int = -1
        private var job: Job? = null
        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null
        private var lastIndexTime = 0L

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        override fun recover() {
            lastSnapshotFlow?.let { initialize(it) }
        }

        fun setError(message: String?) {
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
        }

        fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            lastSnapshotFlow = snapshotFlow
            job?.cancel()
            job =
                scope.launch {
                    snapshotFlow.collectLatest { snapshot ->
                        indexSnapshot(snapshot)
                    }
                }
        }

        private suspend fun ensureSession(forceOverride: Boolean = false): Boolean {
            if (session != null && !forceOverride) return true

            return try {
                val newSession = appSearchWrapper.createSearchSession(context, DATABASE_NAME)
                val request = appSearchWrapper.createSchemaRequest(forceOverride)
                newSession.setSchema(request)
                session = newSession
                _status.value = TrackingStatus.Active
                true
            } catch (e: AppSearchException) {
                if (e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA && !forceOverride) {
                    AppLogger.w("AppSearch schema mismatch, retrying with force override")
                    return ensureSession(forceOverride = true)
                }
                AppLogger.e("Fatal AppSearch error", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
                false
            } catch (e: Exception) {
                AppLogger.e("Failed to initialize AppSearch session", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                false
            }
        }

        suspend fun indexSnapshot(snapshot: GlobalSnapshot) {
            val currentHash = SnapshotUtils.calculateContentHash(snapshot)
            if (currentHash == lastSnapshotHash) return

            val now = timeProvider.now()
            // Allow manual updates to skip throttle, others are delayed to ensure eventual indexing
            if (!snapshot.isManualUpdate) {
                val timeSinceLast = now - lastIndexTime
                if (timeSinceLast < Constants.Semantic.THROTTLE_MS) {
                    delay((Constants.Semantic.THROTTLE_MS - timeSinceLast).milliseconds)
                }
            }

            sessionMutex.withLock {
                if (!ensureSession()) return@withLock

                withContext(ioDispatcher) {
                    try {
                        withTimeout(Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS.milliseconds) {
                            val documents = convertSnapshotToDocuments(snapshot)
                            if (documents.isEmpty()) {
                                lastSnapshotHash = currentHash
                                return@withTimeout
                            }

                            performIndexUpdate(documents, snapshot)
                            lastSnapshotHash = currentHash
                            lastIndexTime = now
                            _status.value = TrackingStatus.Active
                        }
                    } catch (e: Exception) {
                        AppLogger.e("AppSearch indexing error", e)
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    }
                }
            }
        }

        private fun convertSnapshotToDocuments(snapshot: GlobalSnapshot): List<UsageDocument> {
            val documents = mutableListOf<UsageDocument>()
            for (sim in snapshot.simSnapshots) {
                if (!sim.isActive) continue
                for (entity in sim.entities.values) {
                    if (entity.sourceType != DataSourceType.SYSTEM) continue
                    documents.add(UsageDocument.fromSemanticEntity(entity))
                }
            }
            return documents
        }

        private suspend fun performIndexUpdate(documents: List<UsageDocument>, snapshot: GlobalSnapshot) {
            val currentIds = documents.map { it.id }.toSet()

            val activeSlots = snapshot.simSnapshots.filter { it.isActive }.map { it.slot }
            val staleIds = mutableSetOf<String>()
            activeSlots.forEach { slot ->
                val possibleIds = listOf("sim_${slot}_MINUTES", "sim_${slot}_DATA")
                staleIds.addAll(possibleIds.filter { it !in currentIds })
            }

            if (staleIds.isNotEmpty()) {
                session?.removeByDocumentId(createRemoveRequest(staleIds))
            }

            session?.putDocuments(createPutRequest(documents))
        }

        protected open fun createPutRequest(documents: List<UsageDocument>): PutDocumentsRequest =
            PutDocumentsRequest.Builder().addDocuments(documents).build()

        protected open fun createRemoveRequest(ids: Set<String>): RemoveByDocumentIdRequest =
            RemoveByDocumentIdRequest.Builder(NAMESPACE).addIds(ids).build()

        companion object {
            private const val DATABASE_NAME = "usage"
            private const val NAMESPACE = "usage"
        }
    }
