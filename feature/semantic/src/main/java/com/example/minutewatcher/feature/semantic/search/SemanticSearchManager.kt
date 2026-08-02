package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.semantic.SemanticMapper
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.SnapshotUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
open class SemanticSearchManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
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
                            indexSnapshot(snapshot)
                        }
                }
        }

        private suspend fun ensureSession(forceOverride: Boolean = false): Boolean {
            if (session != null && !forceOverride) return true

            var shouldForce = forceOverride
            repeat(2) { attempt ->
                try {
                    val newSession = appSearchWrapper.createSearchSession(context, DATABASE_NAME)
                    val request = appSearchWrapper.createSchemaRequest(shouldForce)
                    newSession.setSchema(request)
                    session = newSession
                    _status.value = TrackingStatus.Active
                    return true
                } catch (e: AppSearchException) {
                    if (e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA && !shouldForce && attempt == 0) {
                        AppLogger.w("AppSearch schema mismatch, retrying with force override")
                        shouldForce = true
                    } else {
                        AppLogger.e("Fatal AppSearch error", e)
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
                        return false
                    }
                } catch (e: Exception) {
                    AppLogger.e("Failed to initialize AppSearch session", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    return false
                }
            }
            return false
        }

        suspend fun indexSnapshot(snapshot: GlobalSnapshot) {
            val currentHash = SnapshotUtils.calculateContentHash(snapshot)
            if (currentHash == lastSnapshotHash) return

            val now = timeProvider.now()

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

        private suspend fun performIndexUpdate(
            documents: List<UsageDocument>,
            snapshot: GlobalSnapshot,
        ) {
            val currentIds = documents.map { it.id }.toSet()

            val activeSlots = snapshot.simSnapshots.filter { it.isActive }.map { it.slot }
            val staleIds = mutableSetOf<String>()
            activeSlots.forEach { slot ->
                val possibleIds =
                    listOf(
                        SemanticMapper.getDocumentId(slot, PackageType.MINUTES),
                        SemanticMapper.getDocumentId(slot, PackageType.DATA),
                    )
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
