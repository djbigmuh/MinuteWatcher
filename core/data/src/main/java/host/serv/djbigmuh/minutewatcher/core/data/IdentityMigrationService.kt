package host.serv.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import host.serv.djbigmuh.minutewatcher.core.common.di.AppDataStore
import host.serv.djbigmuh.minutewatcher.core.common.di.SimDataStore
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.firstWithTimeout
import host.serv.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import host.serv.djbigmuh.minutewatcher.core.data.util.SimIdentityUtils
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.Constants.SimConstants
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

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
            val partialFailure: Boolean = false,
        ) {
            operator fun plus(other: MigrationOutcome): MigrationOutcome =
                MigrationOutcome(
                    migrated = this.migrated + other.migrated,
                    conflicts = this.conflicts + other.conflicts,
                    failures = this.failures + other.failures,
                    // Conflicts are considered a resolved state, not a failure.
                    // We only mark partialFailure if actual technical failures occurred.
                    partialFailure = this.partialFailure || other.partialFailure,
                )

            fun hasChanges(): Boolean = (migrated > 0) || (conflicts > 0)
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
         * @return `true` if legacy keys are found, `false` if schema is current or no legacy keys found,
         * or `null` if the result could not be determined due to read timeout or exception.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * @throws CancellationException if the operation is canceled.
         */
        suspend fun hasLegacyData(activeIdentities: Set<String> = emptySet()): Boolean? =
            // Operation must complete without propagating exception for the legacy data probe;
            // failure degrades to null and must not block startup.
            @Suppress("TooGenericExceptionCaught")
            try {
                val appPrefs =
                    appDataStore.data.firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                        ?: run {
                            AppLogger.w("IdentityMigrationService: App DataStore read timeout in hasLegacyData")
                            return null
                        }
                val currentSchema = appPrefs[DataStoreKeys.DATA_SCHEMA_VERSION] ?: 0
                if (currentSchema >= DataStoreKeys.CURRENT_DATA_SCHEMA_VERSION) {
                    false
                } else {
                    val appHasLegacy = appPrefs.asMap().keys.any { it.isLegacyKey(activeIdentities) }
                    if (appHasLegacy) {
                        return true
                    }
                    val simPrefs =
                        simDataStore.data.firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                            ?: run {
                                AppLogger.w("IdentityMigrationService: Sim DataStore read timeout in hasLegacyData")
                                return null
                            }
                    val simHasLegacy = simPrefs.asMap().keys.any { it.isLegacyKey(activeIdentities) }
                    simHasLegacy
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Operation must complete without propagating exception for legacy data check.
                AppLogger.e("IdentityMigrationService: Error checking for legacy data", e)
                null
            }

        /**
         * Determines if a key is in legacy format requiring migration.
         *
         * Modern identities have the form icc_<hex>/sub_<id>/sim_<n>;
         * their keys are structurally indistinguishable from legacy suffixes. Protection:
         * 1. activeIdentities check is performed FIRST. If the identity is active, the key is NOT considered legacy.
         * 2. Ambiguity is acceptable only while schema version < CURRENT.
         * Keys of INACTIVE modern identities are treated as orphaned-legacy —
         * this is expected: they are migrated/cleaned up.
         */
        private fun Preferences.Key<*>.isLegacyKey(activeIdentities: Set<String>): Boolean {
            val name = this.name
            return when {
                name.startsWith(DataStoreKeys.CONFLICT_PREFIX) -> false
                activeIdentities.any { name.endsWith("_$it") } -> false
                SimConstants.SUPPORTED_SLOT_INDICES.any { name.endsWith("_sim_$it") } ||
                    name.startsWith("${DataStoreKeys.SUBSCRIPTION_ID_PREFIX}icc_") -> true

                else -> LEGACY_SUB_PATTERN.containsMatchIn(name) || LEGACY_ICC_PATTERN.containsMatchIn(name)
            }
        }

        /**
         * Marks the DataStore schema version as current.
         *
         * Should be called after successful completion of all required migrations to avoid
         * redundant checks on subsequent app launches.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * @throws CancellationException if the operation is canceled.
         */
        suspend fun markSchemaCurrent() {
            @Suppress("TooGenericExceptionCaught")
            try {
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    appDataStore.edit { prefs ->
                        prefs[DataStoreKeys.DATA_SCHEMA_VERSION] = DataStoreKeys.CURRENT_DATA_SCHEMA_VERSION
                    }
                } ?: AppLogger.e("IdentityMigrationService: Timeout marking schema current")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Operation must complete without propagating exception for schema version update.
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
         * @throws IOException if DataStore migration fails due to I/O error.
         * @throws CancellationException if the migration process is canceled.
         * @throws Exception if a fatal error occurs during migration execution.
         */
        suspend fun migrateLegacyData(activeIdentity: SimIdentity): MigrationOutcome =
            migrationMutex.withLock {
                @Suppress("TooGenericExceptionCaught")
                try {
                    performMigration(activeIdentity)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for migration logging before rethrow.
                    AppLogger.e("IdentityMigrationService: Fatal error during migration execution", e)
                    throw e
                }
            }

        /**
         * Migrates data from identities generated with an ephemeral seed to those generated with a persistent seed.
         *
         * @param ephemeralSeed The ephemeral seed used during DataStore unavailability.
         * @param activeSims The list of currently active SIM info.
         * @return The combined outcome of all migration attempts.
         */
        suspend fun migrateFromEphemeralSeed(
            ephemeralSeed: String,
            activeSims: List<ActiveSimInfo>,
        ): MigrationOutcome =
            migrationMutex.withLock {
                val mappings = mutableListOf<Pair<String, String>>()
                for (simInfo in activeSims) {
                    val ephemeralIdentity =
                        SimIdentityUtils.createIdentityFromInfo(simInfo, ephemeralSeed)
                    val persistentIdentity = simInfo.identity

                    if (ephemeralIdentity.stableId != persistentIdentity.stableId) {
                        AppLogger.d(
                            "IdentityMigrationService: Preparing ephemeral migration for slot ${simInfo.slotIndex} " +
                                "(${ephemeralIdentity.stableId} -> ${persistentIdentity.stableId})",
                        )
                        mappings.add("_" + ephemeralIdentity.stableId to "_" + persistentIdentity.stableId)
                    }
                }

                if (mappings.isEmpty()) return MigrationOutcome()

                var totalOutcome = MigrationOutcome()
                totalOutcome += migrateDataStoreBatch(appDataStore, mappings, "app_settings")
                totalOutcome += migrateDataStoreBatch(simDataStore, mappings, "sim_settings")
                totalOutcome
            }

        private suspend fun performMigration(activeIdentity: SimIdentity): MigrationOutcome {
            val identity = activeIdentity.stableId
            val slotIndex = activeIdentity.slotIndex

            val legacySuffixes = if (slotIndex != null) findLegacySuffixes(activeIdentity) else emptyList()
            val newSuffix = "_$identity"
            val mappings = legacySuffixes.filter { it != newSuffix }.map { it to newSuffix }

            return if (mappings.isEmpty()) {
                MigrationOutcome()
            } else {
                executeMigrationBatch(activeIdentity, mappings)
            }
        }

        private suspend fun executeMigrationBatch(
            activeIdentity: SimIdentity,
            mappings: List<Pair<String, String>>,
        ): MigrationOutcome {
            var totalOutcome = MigrationOutcome()

            // 1. Batch migrate App Settings
            totalOutcome += migrateDataStoreBatch(appDataStore, mappings, "app_settings")

            // 2. Batch migrate SIM Settings AND Cleanup mapping markers
            val subIdForCleanup =
                if (activeIdentity.source == IdentitySource.SUBSCRIPTION_ID) {
                    activeIdentity.subscriptionId
                } else {
                    null
                }

            totalOutcome +=
                migrateDataStoreBatch(
                    simDataStore,
                    mappings,
                    "sim_settings",
                    subIdForCleanup = subIdForCleanup,
                )

            logMigrationSummary(activeIdentity.stableId, totalOutcome)

            return totalOutcome
        }

        private fun logMigrationSummary(
            identity: String,
            totalOutcome: MigrationOutcome,
        ) {
            if (totalOutcome.hasChanges()) {
                AppLogger.d(
                    "Migration completed for identity $identity: " +
                        "${totalOutcome.migrated} keys migrated, ${totalOutcome.conflicts} conflicts",
                )
            }

            if (totalOutcome.failures > 0) {
                AppLogger.e("Migration for identity $identity finished with ${totalOutcome.failures} failures")
            }
        }

        private suspend fun findLegacySuffixes(activeIdentity: SimIdentity): List<String> {
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
            if (activeIdentity.source == IdentitySource.ICCID) {
                activeIdentity.subscriptionId?.let { subId ->
                    suffixes.add("_sub_$subId")
                }
            }

            // 3. Historical Mapping Migration:
            // If the current SIM is identified by Subscription ID (usually on older Android APIs),
            // it might have legacy data under a previously generated ICCID identity
            // (e.g. if the user upgraded from a newer Android version to an older one,
            // or if ICCID became unavailable due to OS policy changes).
            if (activeIdentity.source == IdentitySource.SUBSCRIPTION_ID) {
                val subId = activeIdentity.subscriptionId
                if (subId != null) {
                    val simPrefs =
                        simDataStore.data.firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                            ?: throw IOException("Sim DataStore read timeout in findLegacySuffixes")
                    // Find all "subscription_id_icc_..." keys that map to this subId.
                    // These keys were created by previous versions to maintain identity stability.
                    val iccIdentities =
                        simPrefs
                            .asMap()
                            .keys
                            .asSequence()
                            .filter { it.name.startsWith("${DataStoreKeys.SUBSCRIPTION_ID_PREFIX}icc_") }
                            .filter { simPrefs[it] == subId }
                            .map { it.name.removePrefix(DataStoreKeys.SUBSCRIPTION_ID_PREFIX) }
                            .toList()

                    iccIdentities.forEach { suffixes.add("_$it") }
                }
            }

            return suffixes.distinct()
        }

        private suspend fun migrateDataStoreBatch(
            dataStore: DataStore<Preferences>,
            mappings: List<Pair<String, String>>,
            storeName: String,
            subIdForCleanup: Int? = null,
        ): MigrationOutcome {
            var migratedCount = 0
            var conflictCount = 0
            var failureCount = 0
            var partialFailure = false

            // Operation must complete without propagating exception for batch migration;
            // failures are recorded in the migration result instead of crashing app startup.
            @Suppress("TooGenericExceptionCaught")
            try {
                val success =
                    withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                        dataStore.edit { prefs ->
                            performHygieneCleanup(prefs)

                            val (migrated, conflicts, failures) = migrateSuffixes(prefs, mappings, storeName)
                            migratedCount = migrated
                            conflictCount = conflicts
                            failureCount = failures

                            if (subIdForCleanup != null) {
                                performSimCleanup(prefs, subIdForCleanup)
                            }
                        }
                        true
                    } ?: false

                if (!success) {
                    AppLogger.e("IdentityMigrationService: Batch migration timed out for $storeName")
                    failureCount = mappings.size
                    partialFailure = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Operation must complete without propagating exception for batch migration.
                AppLogger.e("IdentityMigrationService: Batch migration failed for $storeName", e)
                failureCount = mappings.size
                partialFailure = true
            }

            return MigrationOutcome(
                migrated = migratedCount,
                conflicts = conflictCount,
                failures = failureCount,
                partialFailure = partialFailure || (failureCount > 0),
            )
        }

        private fun performHygieneCleanup(prefs: MutablePreferences) {
            // 0. One-time hygiene: remove orphaned conflict metadata keys
            val conflictMetadataKeys =
                prefs
                    .asMap()
                    .keys
                    .filter {
                        it.name.startsWith(
                            "${DataStoreKeys.CONFLICT_PREFIX}${DataStoreKeys.SUBSCRIPTION_ID_PREFIX}",
                        )
                    }
            conflictMetadataKeys.forEach { prefs.remove(it) }

            // Remove deprecated custom sim operator keys
            val customOperatorKeys =
                prefs
                    .asMap()
                    .keys
                    .filter { it.name.startsWith(DataStoreKeys.CUSTOM_SIM_OPERATOR_PREFIX) }
            customOperatorKeys.forEach { prefs.remove(it) }
        }

        private fun migrateSuffixes(
            prefs: MutablePreferences,
            mappings: List<Pair<String, String>>,
            storeName: String,
        ): Triple<Int, Int, Int> {
            var migratedCount = 0
            var conflictCount = 0
            var failureCount = 0

            for ((legacySuffix, newSuffix) in mappings) {
                // EXCLUDE metadata keys (subscription_id_*) from suffix migration.
                // They are handled by the cleanup block (step 2) or re-written by persistence.
                val keysToMigrate =
                    prefs
                        .asMap()
                        .keys
                        .filter {
                            it.name.endsWith(legacySuffix) &&
                                !it.name.startsWith(DataStoreKeys.SUBSCRIPTION_ID_PREFIX)
                        }

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
                            // Conflict resolved by keeping modern value and backing up legacy to
                            // conflict_<key>. Old legacy key must be removed to prevent infinite
                            // migration loops.
                            prefs.remove(oldKey)
                        }

                        MigrationResult.FAILURE -> {
                            failureCount++
                        }
                    }
                }
            }
            return Triple(migratedCount, conflictCount, failureCount)
        }

        private fun performSimCleanup(
            prefs: MutablePreferences,
            subIdForCleanup: Int,
        ) {
            val keysToRemove =
                prefs
                    .asMap()
                    .keys
                    .asSequence()
                    .filter { it.name.startsWith("${DataStoreKeys.SUBSCRIPTION_ID_PREFIX}icc_") }
                    .filter { prefs[it] == subIdForCleanup }
                    .toList()
            keysToRemove.forEach { prefs.remove(it) }
        }

        private enum class MigrationResult {
            SUCCESS,
            CONFLICT,
            FAILURE,
        }

        private companion object {
            private val LEGACY_SUB_PATTERN = Regex("_sub_\\d+$")
            private val LEGACY_ICC_PATTERN = Regex("_icc_[a-f0-9]+$")
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

                is Set<*> -> {
                    val stringSet = value.filterIsInstance<String>().toSet()
                    migrateOrBackup(
                        stringSetPreferencesKey(newKeyName),
                        stringSetPreferencesKey(conflictKeyName),
                        stringSet,
                        storeName,
                    )
                }

                else -> {
                    AppLogger.e("Unknown type for key ${oldKey.name}: ${value::class.simpleName}, skipping migration")
                    MigrationResult.FAILURE
                }
            }
        }

        /**
         * Migrates a single key-value pair or backs it up if a conflict occurs.
         *
         * **Conflict Resolution Policy:**
         * If a modern identity-based key already exists with a different value than the legacy key:
         * 1. The modern value wins and remains unchanged.
         * 2. The legacy value is backed up to a new key prefixed with "conflict_".
         * 3. The original legacy key is removed (handled by the caller) to mark migration as resolved.
         *
         * @return [MigrationResult.SUCCESS] if migrated or values matched, [MigrationResult.CONFLICT] if backed up.
         */
        private fun <T> MutablePreferences.migrateOrBackup(
            newKey: Preferences.Key<T>,
            conflictKey: Preferences.Key<T>,
            value: T,
            storeName: String,
        ): MigrationResult {
            val existingValue = this[newKey]
            val result =
                when (existingValue) {
                    null -> {
                        this[newKey] = value
                        MigrationResult.SUCCESS
                    }

                    value -> MigrationResult.SUCCESS
                    else -> {
                        this[conflictKey] = value
                        AppLogger.w(
                            "Migration conflict in $storeName: ${newKey.name} already exists. " +
                                "Legacy value backed up to ${conflictKey.name}",
                        )
                        MigrationResult.CONFLICT
                    }
                }
            return result
        }
    }
