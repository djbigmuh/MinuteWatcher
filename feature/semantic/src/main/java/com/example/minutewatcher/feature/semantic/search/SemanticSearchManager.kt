package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.exceptions.AppSearchException
import androidx.appsearch.localstorage.LocalStorage
import com.example.minutewatcher.core.data.di.ApplicationScope
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(FlowPreview::class)
@Singleton
class SemanticSearchManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
    ) : AutoCloseable,
        TrackingStatusProvider {
        override val key: String = "appsearch"

        private var session: AppSearchSession? = null
        private var lastSnapshotHash: Int = 0
        private var job: Job? = null

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)

        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            job?.cancel()
            job =
                scope.launch {
                    _status.value = TrackingStatus.Active
                    try {
                        ensureSession()
                    } catch (t: Throwable) {
                        AppLogger.e("Fatal error during AppSearch session initialization", t)
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, t.message)
                    }

                    snapshotFlow
                        .debounce { if (it.isManualUpdate) 0.seconds else 15.seconds }
                        .collectLatest { snapshot ->
                            if (snapshot.timestamp > 0) {
                                indexSnapshot(snapshot)
                            }
                        }
                }
        }

        private suspend fun ensureSession(): Boolean {
            var retries = 3
            var retryDelay = 1000.milliseconds
            while (retries > 0) {
                var currentSession: AppSearchSession? = null
                try {
                    currentSession =
                        LocalStorage
                            .createSearchSessionAsync(
                                LocalStorage.SearchContext.Builder(context, "minute_watcher_db").build(),
                            ).await()

                    currentSession
                        .setSchemaAsync(
                            SetSchemaRequest
                                .Builder()
                                .addDocumentClasses(UsageDocument::class.java)
                                .setForceOverride(false)
                                .build(),
                        ).await()

                    session = currentSession
                    return true
                } catch (e: AppSearchException) {
                    currentSession?.close()
                    if (e.resultCode == AppSearchResult.RESULT_INTERNAL_ERROR || e.resultCode == AppSearchResult.RESULT_IO_ERROR) {
                        AppLogger.e("AppSearch transient error, retrying in $retryDelay...", e)
                        delay(retryDelay)
                        retryDelay *= 2
                        retries--
                    } else if (e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) {
                        // Attempt force override on schema conflict
                        AppLogger.w("AppSearch schema mismatch, forcing override")
                        try {
                            // Re-open session if needed, but usually we just got one
                            val overrideSession =
                                currentSession ?: LocalStorage.createSearchSessionAsync(
                                    LocalStorage.SearchContext.Builder(context, "minute_watcher_db").build(),
                                ).await()

                            overrideSession
                                .setSchemaAsync(
                                    SetSchemaRequest
                                        .Builder()
                                        .addDocumentClasses(UsageDocument::class.java)
                                        .setForceOverride(true)
                                        .build(),
                                ).await()
                            session = overrideSession
                            return true
                        } catch (ex: Exception) {
                            AppLogger.e("Failed to force AppSearch schema override", ex)
                            break
                        }
                    } else {
                        AppLogger.e("AppSearch fatal error during session creation", e)
                        break
                    }
                } catch (e: Exception) {
                    currentSession?.close()
                    AppLogger.e("Unexpected error during AppSearch initialization", e)
                    break
                }
            }
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, "Failed to init AppSearch")
            return false
        }

        private var lastIndexTime = 0L
        private val minIndexInterval = 5 * 60 * 1000L // 5 minutes

        private suspend fun indexSnapshot(snapshot: GlobalSnapshot) {
            val currentTime = System.currentTimeMillis()
            val currentHash = SnapshotHashCalculator.calculate(snapshot)

            if (currentHash == lastSnapshotHash) return

            if (session == null) {
                if (!ensureSession()) return
            }

            val activeSimSnapshots =
                snapshot.simSnapshots
                    .filter { it.isActive && it.slot in SimConstants.SUPPORTED_SLOT_INDICES }

            // If it's a manual update, ignore the interval
            if (!snapshot.isManualUpdate && activeSimSnapshots.isNotEmpty() && (currentTime - lastIndexTime < minIndexInterval)) return

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
                    session
                        ?.putAsync(
                            PutDocumentsRequest.Builder().addDocuments(documents).build(),
                        )?.await()
                }

                lastIndexTime = currentTime
                lastSnapshotHash = currentHash

                val activeSimSlots = activeSimSnapshots.map { it.slot }.toSet()
                val allSimSlots = SimConstants.SUPPORTED_SLOT_INDICES.toSet()
                val inactiveSimSlots = allSimSlots - activeSimSlots

                inactiveSimSlots.forEach { slot ->
                    val idsToRemove = listOf("sim_${slot}_MINUTES", "sim_${slot}_DATA")
                    session
                        ?.removeAsync(
                            androidx.appsearch.app.RemoveByDocumentIdRequest
                                .Builder("usage")
                                .addIds(idsToRemove)
                                .build(),
                        )?.await()
                }
                _status.value = TrackingStatus.Active
            } catch (e: AppSearchException) {
                AppLogger.e("AppSearch error during indexing", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
                if (e.resultCode != AppSearchResult.RESULT_INTERNAL_ERROR && e.resultCode != AppSearchResult.RESULT_IO_ERROR) {
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

        override fun close() {
            try {
                job?.cancel()
                session?.close()
                session = null
            } catch (e: Exception) {
                AppLogger.e("Error closing AppSearch session", e)
            }
        }
    }
