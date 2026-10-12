package com.example.minutewatcher.feature.semantic.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.example.minutewatcher.core.common.di.AppDataStore
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.SemanticMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles snapshot indexing and stale package type cleanup.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class SnapshotIndexer
    @Inject
    constructor(
        private val appSearchWrapper: AppSearchWrapper,
        private val errorHandler: AppSearchErrorHandler,
        @AppDataStore
        private val dataStore: DataStore<Preferences>,
    ) {
        /**
         * Updates the AppSearch index based on the given snapshot.
         *
         * **Contract:** A snapshot with [Constants.Data.EMPTY_TIMESTAMP] is ignored to prevent
         * accidental index wipes during initialization.
         */
        suspend fun performIndexUpdate(
            session: AppSearchSessionWrapper,
            snapshot: GlobalSnapshot,
            updateStatus: (TrackingStatus) -> Unit = {},
        ): Boolean {
            if (snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP) {
                AppLogger.d("SnapshotIndexer: snapshot not loaded yet, skipping index update (no wipe)")
                return false
            }

            val documents = UsageDocument.fromGlobalSnapshot(snapshot)
            val currentIds = documents.asSequence().map { it.id }.toSet()

            val staleIds = mutableSetOf<String>()
            SimConstants.SUPPORTED_SLOT_INDICES.forEach { slot ->
                val possibleIds =
                    listOf(
                        SemanticMapper.getDocumentId(slot, PackageType.MINUTES),
                        SemanticMapper.getDocumentId(slot, PackageType.DATA),
                    )
                staleIds.addAll(possibleIds.filter { it !in currentIds })
            }

            var putException: Exception? = null
            if (documents.isNotEmpty()) {
                try {
                    session.putDocuments(appSearchWrapper.createPutRequest(documents))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("SnapshotIndexer: failure during putDocuments", e)
                    putException = e
                }
            }

            errorHandler.throwIfErrors(putException, null)

            var removeFailed = false
            if (staleIds.isNotEmpty()) {
                try {
                    session.removeByDocumentId(appSearchWrapper.createRemoveRequest(staleIds))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("SnapshotIndexer: failure during removeByDocumentId", e)
                    removeFailed = true
                    updateStatus(
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            e.message ?: "Stale document removal failed",
                        ),
                    )
                }
            }
            return removeFailed
        }

        suspend fun cleanupStalePackageTypes(
            sessionManager: AppSearchSessionManager,
            updateStatus: (TrackingStatus) -> Unit,
        ) {
            val currentTypes =
                PackageType.entries
                    .asSequence()
                    .map { it.name }
                    .toSet()

            val prefs =
                dataStore.data.firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS) ?: run {
                    AppLogger.w("SnapshotIndexer: DataStore read timeout during cleanupStalePackageTypes")
                    return
                }
            val savedTypes = prefs[KEY_PACKAGE_TYPES] ?: emptySet()

            val staleTypes = savedTypes - currentTypes
            if (staleTypes.isNotEmpty()) {
                AppLogger.d("SnapshotIndexer: cleaning up stale package types: $staleTypes")
                if (sessionManager.ensureSession(updateStatus)) {
                    val staleIds = getStaleIds(currentTypes, savedTypes)
                    if (staleIds.isNotEmpty()) {
                        try {
                            sessionManager.session?.removeByDocumentId(appSearchWrapper.createRemoveRequest(staleIds))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.w("SnapshotIndexer: failed to remove stale documents", e)
                        }
                    }
                }
            }

            updateSavedTypes(currentTypes, savedTypes)
        }

        private suspend fun updateSavedTypes(
            currentTypes: Set<String>,
            savedTypes: Set<String>,
        ) {
            if (savedTypes != currentTypes) {
                try {
                    val updated =
                        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                            dataStore.edit { prefs ->
                                prefs[KEY_PACKAGE_TYPES] = currentTypes
                            }
                        }
                    if (updated == null) {
                        AppLogger.w("SnapshotIndexer: DataStore write timeout during updateSavedTypes")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("SnapshotIndexer: DataStore write failed during updateSavedTypes", e)
                }
            }
        }

        private fun getStaleIds(
            currentTypes: Set<String>,
            savedTypes: Set<String>,
        ): Set<String> {
            val staleTypes = savedTypes - currentTypes
            if (staleTypes.isEmpty()) return emptySet()

            val staleIds = mutableSetOf<String>()
            SimConstants.SUPPORTED_SLOT_INDICES.forEach { slot ->
                staleTypes.forEach { typeName ->
                    staleIds.add(SemanticMapper.getDocumentId(slot, typeName))
                }
            }
            return staleIds
        }

        companion object {
            private val KEY_PACKAGE_TYPES = stringSetPreferencesKey("appsearch_package_types")
        }
    }
