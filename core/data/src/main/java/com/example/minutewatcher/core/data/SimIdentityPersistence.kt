package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.SimDataStore
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles persistence of SIM identities and operator info using [DataStore].
 * Also manages legacy data migrations.
 *
 * Safe to call from any coroutine context.
 *
 * NOTE: TooManyFunctions is suppressed: read/write/migration operations over
 * identity records target one DataStore schema and are reviewed as a single
 * persistence surface.
 */
@Suppress("TooManyFunctions")
@Singleton
class SimIdentityPersistence
    @Inject
    constructor(
        @SimDataStore dataStore: DataStore<Preferences>,
        private val migrationService: IdentityMigrationService,
    ) : BaseDataStoreRepository(dataStore) {
        private val saveMutex = Mutex()
        private val migratedIdentities = ConcurrentHashMap.newKeySet<String>()
        private val migratingIdentities = ConcurrentHashMap.newKeySet<String>()

        /**
         * Saves SIM identities to DataStore and performs migrations if needed.
         */
        suspend fun saveSimIdentitiesInfo(identities: List<SimIdentity>) {
            val validIdentities = filterValidIdentities(identities)
            if (validIdentities.isEmpty()) return

            try {
                withTimeout(SAVE_TIMEOUT_MS.milliseconds) {
                    val preEditPrefs = fetchCurrentPrefs()
                    val toMigrate = validIdentities.filter { isMigrationRequired(it, preEditPrefs) }
                    val failedIds = performMigrations(toMigrate)

                    saveMutex.withLock {
                        val anyUpdateRequired = validIdentities.any { isIdentityUpdateRequired(it, preEditPrefs) }
                        if (!anyUpdateRequired && toMigrate.isEmpty()) return@withLock

                        dataStore.edit { prefs ->
                            val toUpdate = validIdentities.filter { isIdentityUpdateRequired(it, prefs) }
                            val updatedToUpdate = applyOptimizationAndFilterUpdates(toUpdate, prefs)
                            if (updatedToUpdate.isEmpty()) return@edit

                            val toActuallyUpdate = updatedToUpdate.filter { it.stableId !in failedIds }
                            updatePreferences(toActuallyUpdate, prefs)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                AppLogger.e("SimIdentityPersistence: Save operation timed out (mutex, migration or IO)")
                throw IOException("Save operation timed out", e)
            }
        }

        private fun filterValidIdentities(identities: List<SimIdentity>): List<SimIdentity> =
            identities
                .asSequence()
                .filter { !SimConstants.isInvalid(it.stableId) }
                .distinctBy { it.stableId }
                .toList()

        private fun applyOptimizationAndFilterUpdates(
            identities: List<SimIdentity>,
            prefs: MutablePreferences,
        ): List<SimIdentity> =
            identities.filter { identity ->
                if (prefs[DataStoreKeys.slotIndexKey(identity.stableId)] != null) {
                    migratedIdentities.add(identity.stableId)
                }
                isIdentityUpdateRequired(identity, prefs)
            }

        private fun updatePreferences(
            identities: List<SimIdentity>,
            prefs: MutablePreferences,
        ) {
            identities.forEach { identity ->
                identity.slotIndex?.let { slot ->
                    prefs[DataStoreKeys.slotIndexKey(identity.stableId)] = slot
                }
                identity.subscriptionId?.let { subId ->
                    prefs[DataStoreKeys.subscriptionIdKey(identity.stableId)] = subId
                }
            }
        }

        private suspend fun fetchCurrentPrefs(): Preferences =
            dataStore.data.firstWithTimeout() ?: throw IOException("DataStore read timeout")

        private fun isIdentityUpdateRequired(
            identity: SimIdentity,
            prefs: Preferences,
        ): Boolean {
            val slotKey = DataStoreKeys.slotIndexKey(identity.stableId)
            val subKey = DataStoreKeys.subscriptionIdKey(identity.stableId)
            val currentSlot = prefs[slotKey]
            val currentSub = prefs[subKey]
            return (currentSlot != identity.slotIndex) || (currentSub != identity.subscriptionId)
        }

        private fun isMigrationRequired(
            identity: SimIdentity,
            prefs: Preferences,
        ): Boolean {
            val currentSlot = prefs[DataStoreKeys.slotIndexKey(identity.stableId)]
            return (currentSlot == null) && !migratedIdentities.contains(identity.stableId)
        }

        private suspend fun performMigrations(toMigrate: List<SimIdentity>): Set<String> {
            if (toMigrate.isEmpty()) return emptySet()
            val failed = mutableSetOf<String>()
            toMigrate.forEach { identity ->
                try {
                    migrateSingle(identity)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Migration failed for ${identity.stableId} in SimIdentityPersistence", e)
                    failed.add(identity.stableId)
                }
            }
            return failed
        }

        private suspend fun migrateSingle(identity: SimIdentity) {
            if (!migratingIdentities.add(identity.stableId)) return
            try {
                val outcome = migrationService.migrateLegacyData(identity)
                if (outcome.partialFailure) {
                    throw IOException("Migration failed for some keys of ${identity.stableId} (partialFailure=true)")
                }
                migratedIdentities.add(identity.stableId)
            } finally {
                migratingIdentities.remove(identity.stableId)
            }
        }

        fun retainMigrated(currentIdentities: Set<String>) {
            migratedIdentities.retainAll(currentIdentities)
        }

        suspend fun setSimOperator(
            identity: String,
            name: String,
        ) {
            if (SimConstants.isInvalid(identity)) return
            dataStore.edit { it[DataStoreKeys.simOperatorKey(identity)] = name }
        }

        companion object {
            private const val SAVE_TIMEOUT_MS = 60_000L
        }
    }
