package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
                withTimeout(
                    (Constants.Flow.LONG_TIMEOUT_MS * DOUBLE_TIMEOUT_MULTIPLIER).milliseconds,
                ) {
                    saveMutex.withLock {
                        val currentPrefs = fetchCurrentPrefs() ?: return@withLock
                        val toUpdate = validIdentities.filter { isIdentityUpdateRequired(it, currentPrefs) }
                        if (toUpdate.isEmpty()) return@withLock

                        // Perform migrations OUTSIDE dataStore.edit to avoid potential deadlock
                        // and long DataStore locking.
                        val toMigrate = filterIdentitiesRequiringMigration(toUpdate, currentPrefs)
                        val failedIds = performMigrations(toMigrate)

                        dataStore.edit { prefs ->
                            val updatedToUpdate = applyOptimizationAndFilterUpdates(toUpdate, prefs)
                            if (updatedToUpdate.isEmpty()) return@edit

                            val toActuallyUpdate = updatedToUpdate.filter { it.stableId !in failedIds }
                            updatePreferences(toActuallyUpdate, prefs)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                AppLogger.e("SimIdentityPersistence: Save operation timed out (mutex or IO)")
                throw IOException("Save operation timed out", e)
            } catch (e: CancellationException) {
                throw e
            }
        }

        private fun filterValidIdentities(identities: List<SimIdentity>): List<SimIdentity> =
            identities
                .asSequence()
                .filter {
                    (it.source != IdentitySource.SLOT_FALLBACK) &&
                        (!SimConstants.isInvalid(it.stableId))
                }.distinctBy { it.stableId }
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

        private fun filterIdentitiesRequiringMigration(
            identities: List<SimIdentity>,
            prefs: Preferences,
        ): List<SimIdentity> = identities.filter { isMigrationRequired(it, prefs) }

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

        private suspend fun fetchCurrentPrefs(timeoutMs: Long = Constants.Flow.LONG_TIMEOUT_MS): Preferences? =
            dataStore.data.firstWithTimeout(timeoutMs)

        private fun isIdentityUpdateRequired(
            identity: SimIdentity,
            prefs: Preferences,
        ): Boolean {
            val currentSlot = prefs[DataStoreKeys.slotIndexKey(identity.stableId)]
            val currentSub = prefs[DataStoreKeys.subscriptionIdKey(identity.stableId)]
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
                migrationService.migrateLegacyData(identity)
                migratedIdentities.add(identity.stableId)
            } finally {
                migratingIdentities.remove(identity.stableId)
            }
        }

        fun retainMigrated(currentIdentities: Set<String>) {
            migratedIdentities.retainAll(currentIdentities)
        }

        fun getSimOperator(identity: String): Flow<String> {
            if (identity.startsWith(
                    SimConstants.INVALID_SLOT_IDENTITY_PREFIX,
                )
            ) {
                return kotlinx.coroutines.flow.flowOf("")
            }
            return combine(
                getData(DataStoreKeys.simOperatorKey(identity), ""),
                getCustomSimOperator(identity),
            ) { resolved, custom ->
                custom ?: resolved
            }
        }

        fun getCustomSimOperator(identity: String): Flow<String?> {
            if (identity.startsWith(
                    SimConstants.INVALID_SLOT_IDENTITY_PREFIX,
                )
            ) {
                return kotlinx.coroutines.flow.flowOf(null)
            }
            return dataStore.data.map { it[DataStoreKeys.customSimOperatorKey(identity)] }
        }

        suspend fun setSimOperator(
            identity: String,
            name: String,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) return
            dataStore.edit { it[DataStoreKeys.simOperatorKey(identity)] = name }
        }

        suspend fun setCustomSimOperator(
            identity: String,
            name: String?,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) return
            dataStore.edit { prefs ->
                if (name == null) {
                    prefs.remove(DataStoreKeys.customSimOperatorKey(identity))
                } else {
                    prefs[DataStoreKeys.customSimOperatorKey(identity)] = name
                }
            }
        }

        fun getSimCode(identity: String): Flow<String> {
            if (identity.startsWith(
                    SimConstants.INVALID_SLOT_IDENTITY_PREFIX,
                )
            ) {
                return kotlinx.coroutines.flow.flowOf("")
            }
            return getData(DataStoreKeys.simCodeKey(identity), "")
        }

        suspend fun setSimCode(
            identity: String,
            code: String,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) return
            dataStore.edit { it[DataStoreKeys.simCodeKey(identity)] = code }
        }

        companion object {
            private const val DOUBLE_TIMEOUT_MULTIPLIER = 2
        }
    }
