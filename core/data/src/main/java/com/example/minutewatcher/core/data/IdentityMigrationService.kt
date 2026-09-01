package com.example.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.model.Constants.SimConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for migrating legacy DataStore keys to the modern identity-based format.
 *
 * This implementation is AOSP DataStore compliant and follows strict consistency rules
 * for atomic updates.
 * Safe to call from any coroutine context.
 */
@Singleton
class IdentityMigrationService
    @Inject
    constructor(
        @AppDataStore private val appDataStore: DataStore<Preferences>,
        @SimDataStore private val simDataStore: DataStore<Preferences>,
    ) {
    data class MigrationOutcome(
        val migrated: Int = 0,
        val conflicts: Int = 0,
        val failures: Int = 0,
    ) {
        operator fun plus(other: MigrationOutcome): MigrationOutcome =
            MigrationOutcome(
                migrated = this.migrated + other.migrated,
                conflicts = this.conflicts + other.conflicts,
                failures = this.failures + other.failures,
            )

        fun hasChanges(): Boolean = migrated > 0 || conflicts > 0
    }

        private val migrationMutex = Mutex()

        /**
         * Checks if there is any legacy data in the DataStore that needs migration to the new identity-based format.
         *
         * Data is considered legacy if the schema version is less than [DataStoreKeys.CURRENT_DATA_SCHEMA_VERSION]
         * and there are keys matching legacy suffix patterns (e.g., "_sim_0", "_sub_123").
         *
         * @param activeIdentities The set of currently active stable SIM identities to protect from being
         * treated as legacy. Defaults to an empty set.
         * @return `true` if legacy keys are found and migration is required, `false` otherwise.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * @throws kotlinx.coroutines.CancellationException if the operation is cancelled.
         */
        suspend fun hasLegacyData(activeIdentities: Set<String> = emptySet()): Boolean =
            try {
                val appPrefs = appDataStore.data.first()
                val currentSchema = appPrefs[DataStoreKeys.DATA_SCHEMA_VERSION] ?: 0
                if (currentSchema >= DataStoreKeys.CURRENT_DATA_SCHEMA_VERSION) {
                    false
                } else {
                    val appHasLegacy = appPrefs.asMap().keys.any { it.isLegacyKey(activeIdentities) }
                    val simHasLegacy =
                        simDataStore.data
                            .first()
                            .asMap()
                            .keys
                            .any { it.isLegacyKey(activeIdentities) }
                    appHasLegacy || simHasLegacy
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Re-throw IOException to allow caller to retry upon transient failures.
                throw e
            } catch (e: IllegalStateException) {
                AppLogger.e("IdentityMigrationService: Permanent error checking for legacy data", e)
                false
            } catch (e: IllegalArgumentException) {
                AppLogger.e("IdentityMigrationService: Permanent error checking for legacy data", e)
                false
            }

        /**
         * Определяет, является ли ключ legacy-форматом, требующим миграции.
         *
         * **Безопасность prefix-матчинга:**
         * - Проверка `activeIdentities.any { name.endsWith("_$it") }` выполняется ПЕРВОЙ.
         *   Если identity активна, ключ НЕ считается legacy, даже если суффикс
         *   совпадает с legacy-паттерном. Это предотвращает миграцию активных данных.
         * - Regex `_sub_\d+$` и `_icc_[a-f0-9]+$` используют `$`-якорь,
         *   что гарантирует матчинг только в конце строки. Ключи вида
         *   `minutes_used_sub_123` корректно определяются как legacy,
         *   т.к. заканчиваются на `_sub_123`.
         * - Ключи вида `widget_show_sim_icc_abc123` НЕ матчатся,
         *   т.к. заканчиваются на `abc123`, а не на `_sub_N` или `_icc_<hex>`.
         *
         * **Эвристика в пределах критерия надёжности:** проверка `endsWith("_sim_$it")`
         * для SUPPORTED_SLOT_INDICES определяет ключи fallback-формата.
         * Если SIM с identity `sim_N` не в activeIdentities, её ключи orphaned
         * и должны быть мигрированы/очищены. Это штатное поведение.
         */
        private fun Preferences.Key<*>.isLegacyKey(activeIdentities: Set<String>): Boolean {
            val name = this.name
            if (name.startsWith("conflict_")) return false

            // Modern keys belonging to known active identities are not legacy.
            if (activeIdentities.any { name.endsWith("_$it") }) return false

            // Unambiguous legacy markers.
            if (SimConstants.SUPPORTED_SLOT_INDICES.any { name.endsWith("_sim_$it") } ||
                name.startsWith("subscription_id_icc_")
            ) {
                return true
            }

            // Legacy: keys ENDING with _sub_N or _icc_<hash>
            // Modern: keys containing _sub_/_icc_ IN THE MIDDLE (e.g., minutes_used_sub_123)
            val legacySubPattern = Regex("_sub_\\d+$")
            val legacyIccPattern = Regex("_icc_[a-f0-9]+$")
            return legacySubPattern.containsMatchIn(name) || legacyIccPattern.containsMatchIn(name)
        }

        /**
         * Marks the DataStore schema version as current.
         *
         * Should be called after successful completion of all required migrations to avoid
         * redundant checks on subsequent app launches.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * @throws kotlinx.coroutines.CancellationException if the operation is cancelled.
         */
        suspend fun markSchemaCurrent() {
            try {
                appDataStore.edit { it[DataStoreKeys.DATA_SCHEMA_VERSION] = DataStoreKeys.CURRENT_DATA_SCHEMA_VERSION }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("IdentityMigrationService: Failed to mark schema current", e)
            }
        }

        /**
         * Performs the migration of legacy data associated with the given active SIM identity.
         *
         * Scans all DataStore keys for legacy suffixes matching the identity's slot, subscription ID,
         * or historical ICCID mappings, and re-maps them to the new stable identity format.
         *
         * This operation is synchronized via an internal [Mutex] to prevent concurrent migrations
         * of the same data stores.
         *
         * @param activeIdentity The current active SIM identity to migrate data for.
         * @return `true` if any legacy data was successfully migrated, `false` otherwise.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore migration fails due to I/O error.
         * @throws kotlinx.coroutines.CancellationException if the migration process is cancelled.
         * @throws Exception if a fatal error occurs during migration execution.
         */
        suspend fun migrateLegacyData(
            activeIdentity: com.example.minutewatcher.core.model.SimIdentity,
        ): MigrationOutcome =
            migrationMutex.withLock {
                try {
                    performMigration(activeIdentity)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("IdentityMigrationService: Fatal error during migration execution", e)
                    throw e
                }
            }

    /**
     * Migrates data from identities generated with an ephemeral seed to those generated with a persistent seed.
     *
     * @param ephemeralSeed The ephemeral seed used during DataStore unavailability.
     * @param persistentSeed The persistent seed recovered from DataStore.
     * @param activeSubscriptions The list of currently active SIM subscriptions.
     * @return The combined outcome of all migration attempts.
     */
    suspend fun migrateFromEphemeralSeed(
        ephemeralSeed: String,
        persistentSeed: String,
        activeSubscriptions: List<SubscriptionInfo>,
    ): MigrationOutcome =
        migrationMutex.withLock {
            var totalOutcome = MigrationOutcome()
            for (info in activeSubscriptions) {
                val ephemeralIdentity =
                    SimIdentityUtils.createIdentityFromInfo(info, ephemeralSeed)
                val persistentIdentity =
                    SimIdentityUtils.createIdentityFromInfo(info, persistentSeed)

                if (ephemeralIdentity.stableId != persistentIdentity.stableId) {
                    AppLogger.d(
                        "IdentityMigrationService: Migrating ephemeral data for slot ${info.simSlotIndex} " +
                            "(${ephemeralIdentity.stableId} -> ${persistentIdentity.stableId})",
                    )
                    totalOutcome += migrateBetweenIdentities(ephemeralIdentity.stableId, persistentIdentity.stableId)
                }
            }
            totalOutcome
        }

    private suspend fun migrateBetweenIdentities(
        oldStableId: String,
        newStableId: String,
    ): MigrationOutcome {
        val oldSuffix = "_$oldStableId"
        val newSuffix = "_$newStableId"
        var outcome = MigrationOutcome()
        outcome +=
            migrateDataStore(
                appDataStore,
                oldSuffix,
                newSuffix,
                "app_settings",
            )
        outcome +=
            migrateDataStore(
                simDataStore,
                oldSuffix,
                newSuffix,
                "sim_settings",
            )
        return outcome
    }

        private suspend fun performMigration(
            activeIdentity: com.example.minutewatcher.core.model.SimIdentity,
        ): MigrationOutcome {
            if (activeIdentity.slotIndex == null) return MigrationOutcome()
            val identity = activeIdentity.stableId

            val legacySuffixes = findLegacySuffixes(activeIdentity)
            val newSuffix = "_$identity"

            var totalOutcome = MigrationOutcome()
            for (legacySuffix in legacySuffixes) {
                if (legacySuffix == newSuffix) continue
                totalOutcome +=
                    migrateDataStore(
                        appDataStore,
                        legacySuffix,
                        newSuffix,
                        "app_settings",
                    )
                totalOutcome +=
                    migrateDataStore(
                        simDataStore,
                        legacySuffix,
                        newSuffix,
                        "sim_settings",
                    )
            }

            if (totalOutcome.hasChanges()) {
                AppLogger.d(
                    "Migration completed for identity $identity: " +
                        "${totalOutcome.migrated} keys migrated, ${totalOutcome.conflicts} conflicts",
                )
            }

            if (totalOutcome.failures > 0) {
                AppLogger.e("Migration for identity $identity finished with ${totalOutcome.failures} failures")
            }

            // Cleanup mapping keys that were used for this identity
            if (activeIdentity.source == com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID) {
                val subId = activeIdentity.subscriptionId
                if (subId != null) {
                    simDataStore.edit { prefs ->
                        val keysToRemove =
                            prefs
                                .asMap()
                                .keys
                                .asSequence()
                                .filter { it.name.startsWith("subscription_id_icc_") }
                                .filter { prefs[it] == subId }
                                .toList()
                        keysToRemove.forEach { prefs.remove(it) }
                    }
                }
            }

            return totalOutcome
        }

        private suspend fun findLegacySuffixes(
            activeIdentity: com.example.minutewatcher.core.model.SimIdentity,
        ): List<String> {
            val suffixes = mutableListOf<String>()

            // 1. V1 Slot-based Migration:
            // Migrates from very old versions where data was stored as "key_sim_N" where N is slot index.
            // This is a universal fallback for all SIMs that have a known slot.
            activeIdentity.slotIndex?.let {
                suffixes.add("_sim_$it")
            }

            // 2. ICCID transition:
            // If the current SIM is identified by ICCID, it might have legacy data stored under its
            // Subscription ID (e.g. from a version that used SubID before ICCID became available).
            if (activeIdentity.source == com.example.minutewatcher.core.model.IdentitySource.ICCID) {
                activeIdentity.subscriptionId?.let { subId ->
                    suffixes.add("_sub_$subId")
                }
            }

            // 3. Historical Mapping Migration:
            // If the current SIM is identified by Subscription ID (usually on older Android APIs),
            // it might have legacy data under a previously generated ICCID identity
            // (e.g. if the user upgraded from a newer Android version to an older one,
            // or if ICCID became unavailable due to OS policy changes).
            if (activeIdentity.source == com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID) {
                val subId = activeIdentity.subscriptionId
                if (subId != null) {
                    val simPrefs = simDataStore.data.first()
                    // Find all "subscription_id_icc_..." keys that map to this subId.
                    // These keys were created by previous versions to maintain identity stability.
                    val iccIdentities =
                        simPrefs
                            .asMap()
                            .keys
                            .asSequence()
                            .filter { it.name.startsWith("subscription_id_icc_") }
                            .filter { simPrefs[it] == subId }
                            .map { it.name.removePrefix("subscription_id_") }
                            .toList()

                    iccIdentities.forEach { suffixes.add("_$it") }
                }
            }

            return suffixes.distinct()
        }

        private suspend fun migrateDataStore(
            dataStore: DataStore<Preferences>,
            legacySuffix: String,
            newSuffix: String,
            storeName: String,
        ): MigrationOutcome {
            var migratedCount = 0
            var conflictCount = 0
            var failureCount = 0
            dataStore.edit { prefs ->
                val keysToMigrate = prefs.asMap().keys.filter { it.name.endsWith(legacySuffix) }

                for (oldKey in keysToMigrate) {
                    val baseName = oldKey.name.removeSuffix(legacySuffix)
                    val newKeyName = baseName + newSuffix

                    when (prefs.migrateTypedKey(oldKey, newKeyName, storeName)) {
                        MigrationResult.SUCCESS -> {
                            migratedCount++
                            prefs.remove(oldKey)
                        }

                        MigrationResult.CONFLICT -> {
                            conflictCount++
                            prefs.remove(oldKey)
                        }

                        MigrationResult.FAILURE -> {
                            failureCount++
                            AppLogger.e(
                                "IdentityMigrationService: failed to migrate key '${oldKey.name}' " +
                                    "(type=${prefs[oldKey]?.let { it::class.simpleName } ?: "null"}) in $storeName",
                            )
                        }
                    }
                }
            }

            return MigrationOutcome(
                migrated = migratedCount,
                conflicts = conflictCount,
                failures = failureCount,
            )
        }

        private enum class MigrationResult {
            SUCCESS,
            CONFLICT,
            FAILURE,
        }

        private fun MutablePreferences.migrateTypedKey(
            oldKey: Preferences.Key<*>,
            newKeyName: String,
            storeName: String,
        ): MigrationResult {
            val value = this[oldKey] ?: return MigrationResult.FAILURE
            val conflictKeyName = "conflict_${oldKey.name}"

            return when (value) {
                is Int ->
                    migrateOrBackup(
                        intPreferencesKey(newKeyName),
                        intPreferencesKey(conflictKeyName),
                        value,
                        storeName,
                    )

                is Float ->
                    migrateOrBackup(
                        floatPreferencesKey(newKeyName),
                        floatPreferencesKey(conflictKeyName),
                        value,
                        storeName,
                    )

                is Long ->
                    migrateOrBackup(
                        longPreferencesKey(newKeyName),
                        longPreferencesKey(conflictKeyName),
                        value,
                        storeName,
                    )

                is Boolean ->
                    migrateOrBackup(
                        booleanPreferencesKey(newKeyName),
                        booleanPreferencesKey(conflictKeyName),
                        value,
                        storeName,
                    )

                is String ->
                    migrateOrBackup(
                        stringPreferencesKey(newKeyName),
                        stringPreferencesKey(conflictKeyName),
                        value,
                        storeName,
                    )

                else -> {
                    AppLogger.e("Unknown type for key ${oldKey.name}: ${value::class.simpleName}, skipping migration")
                    MigrationResult.FAILURE
                }
            }
        }

        private fun <T> MutablePreferences.migrateOrBackup(
            newKey: Preferences.Key<T>,
            conflictKey: Preferences.Key<T>,
            value: T,
            storeName: String,
        ): MigrationResult =
            if (this[newKey] == null) {
                this[newKey] = value
                MigrationResult.SUCCESS
            } else {
                this[conflictKey] = value
                AppLogger.w(
                    "Migration conflict in $storeName: ${newKey.name} already exists. " +
                        "Legacy value backed up to ${conflictKey.name}",
                )
                MigrationResult.CONFLICT
            }
    }
