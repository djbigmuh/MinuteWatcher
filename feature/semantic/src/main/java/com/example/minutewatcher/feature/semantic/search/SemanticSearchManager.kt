package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.exceptions.AppSearchException
import androidx.appsearch.localstorage.LocalStorage
import com.example.minutewatcher.core.data.di.ApplicationScope
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.guava.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@OptIn(FlowPreview::class)
@Singleton
class SemanticSearchManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
    ) : AutoCloseable {
        private var session: AppSearchSession? = null
        private var lastSnapshotHash: Int = 0
        private var job: Job? = null

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)

        @Suppress("unused")
        val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            job?.cancel()
            job =
                scope.launch {
                    _status.value = TrackingStatus.Active
                    try {
                        session =
                            LocalStorage
                                .createSearchSessionAsync(
                                    LocalStorage.SearchContext.Builder(context, "minute_watcher_db").build(),
                                ).await()

                        session
                            ?.setSchemaAsync(
                                SetSchemaRequest.Builder().addDocumentClasses(UsageDocument::class.java).build(),
                            )?.await()

                        snapshotFlow
                            .collectLatest { snapshot: GlobalSnapshot ->
                                if (snapshot.timestamp > 0) {
                                    if (snapshot.isManualUpdate) {
                                        indexSnapshot(snapshot)
                                    } else {
                                        delay(15.seconds) // Debounce for background updates
                                        indexSnapshot(snapshot)
                                    }
                                }
                            }
                    } catch (e: AppSearchException) {
                        AppLogger.e("AppSearch error during initialization", e)
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
                    } catch (e: Exception) {
                        AppLogger.e("Unexpected error during AppSearch initialization", e)
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    }
                }
        }

        private var lastIndexTime = 0L
        private val minIndexInterval = 5 * 60 * 1000L // 5 minutes

        private suspend fun indexSnapshot(snapshot: GlobalSnapshot) {
            val currentTime = System.currentTimeMillis()
            val currentHash = snapshot.hashCode()

            if (currentHash == lastSnapshotHash) return

            val activeSimSnapshots = snapshot.simSnapshots.filter { it.isActive }

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
                val allSimSlots = setOf(0, 1)
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
            } catch (e: Exception) {
                AppLogger.e("Failed to update AppSearch index", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
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
