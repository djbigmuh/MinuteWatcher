package com.example.minutewatcher.feature.semantic.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.semantic.SemanticMapper
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles snapshot indexing and stale package type cleanup.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
open class SnapshotIndexer
    @Inject
    constructor(
        private val appSearchWrapper: AppSearchWrapper,
        private val errorHandler: AppSearchErrorHandler,
        @com.example.minutewatcher.core.data.di.AppDataStore
        private val dataStore: DataStore<Preferences>,
    ) {
        suspend fun performIndexUpdate(
            session: AppSearchSessionWrapper,
            snapshot: GlobalSnapshot,
        ) {
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

            var removeException: Exception? = null
            if (staleIds.isNotEmpty()) {
                try {
                    session.removeByDocumentId(appSearchWrapper.createRemoveRequest(staleIds))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("SnapshotIndexer: failure during removeByDocumentId", e)
                    removeException = e
                }
            }

            var putException: Exception? = null
            if ((removeException == null) && documents.isNotEmpty()) {
                try {
                    session.putDocuments(appSearchWrapper.createPutRequest(documents))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("SnapshotIndexer: failure during putDocuments", e)
                    putException = e
                }
            }

            errorHandler.throwIfErrors(putException, removeException)
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
            val savedTypes = dataStore.data.first()[KEY_PACKAGE_TYPES] ?: emptySet()

            val staleTypes = savedTypes - currentTypes
            if (staleTypes.isEmpty()) {
                updateSavedTypes(currentTypes, savedTypes)
                return
            }

            AppLogger.d("SnapshotIndexer: cleaning up stale package types: $staleTypes")
            if (!sessionManager.ensureSession(updateStatus)) {
                updateSavedTypes(currentTypes, savedTypes)
                return
            }

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

            updateSavedTypes(currentTypes, savedTypes)
        }

        private suspend fun updateSavedTypes(
            currentTypes: Set<String>,
            savedTypes: Set<String>,
        ) {
            if (savedTypes != currentTypes) {
                dataStore.edit { prefs ->
                    prefs[KEY_PACKAGE_TYPES] = currentTypes
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
                    staleIds.add("sim_${slot}_${typeName.lowercase()}")
                }
            }
            return staleIds
        }

        companion object {
            private val KEY_PACKAGE_TYPES = stringSetPreferencesKey("appsearch_package_types")
        }
    }
