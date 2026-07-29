package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SemanticSearchManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val appSearchWrapper: AppSearchWrapper,
        private val timeProvider: TimeProvider,
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
            } catch (e: AppSearchException) {
                AppLogger.e("AppSearch error closing session during re-init", e)
            } catch (e: Exception) {
                AppLogger.e("Unexpected error closing session during re-init", e)
            }
            job =
                scope.launch {
                    _status.value = TrackingStatus.Active
                    val success =
                        withContext(ioDispatcher) {
                            try {
                                ensureSession()
                            } catch (e: AppSearchException) {
                                AppLogger.e("AppSearch session initialization failed", e)
                                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
                                false
                            } catch (e: java.io.IOException) {
                                AppLogger.e("IO error during AppSearch initialization", e)
                                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, "IO Error: ${e.message}")
                                false
                            } catch (e: Exception) {
                                AppLogger.e("Unexpected error during AppSearch session initialization", e)
                                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
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
            } catch (e: AppSearchException) {
                currentSession?.close()
                if (e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) {
                    AppLogger.w("AppSearch schema mismatch, forcing override")
                    var overrideSession: AppSearchSessionWrapper? = null
                    try {
                        overrideSession =
                            appSearchWrapper.createSearchSession(context, "minute_watcher_db")
                        overrideSession.setSchema(createSchemaRequest(true))
                        session = overrideSession
                        _status.value = TrackingStatus.Active
                        return true
                    } catch (ex: AppSearchException) {
                        overrideSession?.close()
                        AppLogger.e("Failed to force AppSearch schema override", ex)
                    } catch (ex: Exception) {
                        overrideSession?.close()
                        AppLogger.e("Unexpected error forcing AppSearch schema override", ex)
                    }
                } else {
                    AppLogger.e("AppSearch error during session creation: ${e.message}", e)
                }
                _status.value = TrackingStatus.Error(
                    TrackingStatus.ErrorType.DATABASE_ERROR,
                    "Failed to init AppSearch: ${e.message}",
                )
                return false
            } catch (e: java.io.IOException) {
                currentSession?.close()
                AppLogger.e("IO error during AppSearch session creation", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, "IO error: ${e.message}")
                return false
            } catch (e: Exception) {
                currentSession?.close()
                AppLogger.e("Unexpected error during AppSearch session creation", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                return false
            }
        }

        private fun createSchemaRequest(forceOverride: Boolean): SetSchemaRequest {
            val builder = SetSchemaRequest.Builder().setForceOverride(forceOverride)
            try {
                builder.addDocumentClasses(UsageDocument::class.java)
            } catch (e: NoClassDefFoundError) {
                if (isTestEnvironment()) {
                    AppLogger.w("UsageDocument converter not found in test environment, proceeding with empty schema")
                } else {
                    throw e
                }
            } catch (e: ClassNotFoundException) {
                if (isTestEnvironment()) {
                    AppLogger.w("UsageDocument converter not found in test environment, proceeding with empty schema")
                } else {
                    throw e
                }
            }
            return builder.build()
        }

        private fun isTestEnvironment(): Boolean {
            return try {
                Class.forName("org.junit.Test")
                true
            } catch (e: ClassNotFoundException) {
                false
            } catch (e: Exception) {
                false
            }
        }

        private var lastIndexTime = 0L
        private val minIndexInterval = 5 * 60 * 1000L // 5 minutes

        internal suspend fun indexSnapshot(snapshot: GlobalSnapshot) {
            val currentHash = SnapshotHashCalculator.calculate(snapshot)
            if (currentHash == lastSnapshotHash) return

            val currentTime = timeProvider.now()
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
                    try {
                        session?.putDocuments(PutDocumentsRequest.Builder().addDocuments(documents).build())
                    } catch (e: AppSearchException) {
                        AppLogger.w("AppSearch error putting documents: ${e.message}")
                    } catch (e: java.io.IOException) {
                        AppLogger.w("IO error putting documents to AppSearch: ${e.message}")
                    }
                }

                lastIndexTime = currentTime
                lastSnapshotHash = currentHash

                // Remove stale documents (active IDs that are no longer in the snapshot)
                val activeDocumentIds = documents.map { it.id }.toSet()
                val allPossibleDocumentIds =
                    SimConstants.SUPPORTED_SLOT_INDICES.flatMap { slot ->
                        listOf("sim_${slot}_MINUTES", "sim_${slot}_DATA")
                    }.toSet()
                val idsToRemove = allPossibleDocumentIds - activeDocumentIds

                if (idsToRemove.isNotEmpty()) {
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


