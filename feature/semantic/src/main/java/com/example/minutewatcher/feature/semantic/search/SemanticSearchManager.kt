package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.data.di.ApplicationScope
import com.example.minutewatcher.core.data.di.IoDispatcher
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
@Singleton
class SemanticSearchManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val appSearchWrapper: AppSearchWrapper,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : TrackingStatusProvider {
        override val key: String = "appsearch"

        private var session: AppSearchSessionWrapper? = null
        private var lastSnapshotHash: Int = 0
        private var job: Job? = null
        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)

        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            lastSnapshotFlow = snapshotFlow
            job?.cancel()
            try {
                session?.close()
                session = null
            } catch (e: Exception) {
                AppLogger.e("Error closing previous AppSearch session during re-init", e)
            }
            job =
                scope.launch {
                    _status.value = TrackingStatus.Active
                    val success =
                        withContext(ioDispatcher) {
                            try {
                                ensureSession()
                            } catch (t: Throwable) {
                                AppLogger.e("Fatal error during AppSearch session initialization", t)
                                _status.value =
                                    TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, t.message)
                                false
                            }
                        }

                    if (success) {
                        snapshotFlow
                            .collectLatest { snapshot ->
                                if (snapshot.timestamp > 0) {
                                    withContext(ioDispatcher) {
                                        indexSnapshot(snapshot)
                                    }
                                }
                            }
                    }
                }
        }

        override fun recover() {
            lastSnapshotFlow?.let { initialize(it) }
        }

        private suspend fun ensureSession(): Boolean {
            var currentSession: AppSearchSessionWrapper? = null
            try {
                currentSession = appSearchWrapper.createSearchSession(context, "minute_watcher_db")
                currentSession.setSchema(createSchemaRequest(false))
                session = currentSession
                _status.value = TrackingStatus.Active
                return true
            } catch (e: Throwable) {
                currentSession?.close()
                if (e is AppSearchException && e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) {
                    AppLogger.w("AppSearch schema mismatch, forcing override")
                    var overrideSession: AppSearchSessionWrapper? = null
                    try {
                        overrideSession =
                            appSearchWrapper.createSearchSession(context, "minute_watcher_db")
                        overrideSession.setSchema(createSchemaRequest(true))
                        session = overrideSession
                        _status.value = TrackingStatus.Active
                        return true
                    } catch (ex: Exception) {
                        overrideSession?.close()
                        AppLogger.e("Failed to force AppSearch schema override", ex)
                    }
                } else {
                    AppLogger.e("AppSearch error during session creation: ${e.message}", e)
                }
            }
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, "Failed to init AppSearch")
            return false
        }

        private fun createSchemaRequest(forceOverride: Boolean): SetSchemaRequest {
            val builder = SetSchemaRequest.Builder().setForceOverride(forceOverride)
            try {
                builder.addDocumentClasses(UsageDocument::class.java)
            } catch (e: Throwable) {
                // Generated code might be missing in some environments (e.g. unit tests)
                AppLogger.w("UsageDocument converter not found, proceeding with empty schema for logic verification")
            }
            return builder.build()
        }

        private var lastIndexTime = 0L
        private val minIndexInterval = 5 * 60 * 1000L // 5 minutes

        private suspend fun indexSnapshot(snapshot: GlobalSnapshot) {
            val currentHash = SnapshotHashCalculator.calculate(snapshot)
            if (currentHash == lastSnapshotHash) return

            val currentTime = System.currentTimeMillis()
            // If it's a manual update, ignore the interval
            if (!snapshot.isManualUpdate && (currentTime - lastIndexTime < minIndexInterval)) return

            if (session == null) {
                if (!ensureSession()) return
            }

            val activeSimSnapshots =
                snapshot.simSnapshots
                    .filter { it.isActive && it.slot in SimConstants.SUPPORTED_SLOT_INDICES }

            val documents =
                activeSimSnapshots.flatMap { sim ->
                    sim.entities.values.map { entity ->
                        UsageDocument(
                            namespace = "usage",
                            id = "sim_${sim.slot}_${entity.packageType.name}",
                            operatorName = sim.operator,
                            packageType = entity.packageType.name,
                            remainingValue = entity.remaining,
                            unit = entity.unit.name,
                        )
                    }
                }

            try {
                if (documents.isNotEmpty()) {
                    session?.putDocuments(PutDocumentsRequest.Builder().addDocuments(documents).build())
                }

                lastIndexTime = currentTime
                lastSnapshotHash = currentHash

                val activeSimSlots = activeSimSnapshots.map { it.slot }.toSet()
                val allSimSlots = SimConstants.SUPPORTED_SLOT_INDICES.toSet()
                val inactiveSimSlots = allSimSlots - activeSimSlots

                inactiveSimSlots.forEach { slot ->
                    val idsToRemove = listOf("sim_${slot}_MINUTES", "sim_${slot}_DATA")
                    session?.removeByDocumentId(
                        RemoveByDocumentIdRequest
                            .Builder("usage")
                            .addIds(idsToRemove)
                            .build(),
                    )
                }
                _status.value = TrackingStatus.Active
            } catch (e: AppSearchException) {
                AppLogger.e("AppSearch error during indexing", e)
                _status.value =
                    TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
                if (e.resultCode != AppSearchResult.RESULT_INTERNAL_ERROR &&
                    e.resultCode != AppSearchResult.RESULT_IO_ERROR
                ) {
                    // Fatal error, close and reset session to trigger re-init next time
                    session?.close()
                    session = null
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to update AppSearch index", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                session?.close()
                session = null
            }
        }
    }
