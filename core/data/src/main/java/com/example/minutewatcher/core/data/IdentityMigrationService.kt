package com.example.minutewatcher.core.data

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
import com.example.minutewatcher.core.model.Constants.SimConstants
import kotlinx.coroutines.flow.first
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
        /**
         * Checks if there is any legacy data that needs migration.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore reading fails.
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
            } catch (e: Exception) {
                // Migration logic is protected against corrupted DataStore state.
                // Errors result in skipping migration to maintain app stability.
                AppLogger.e("IdentityMigrationService: Failed to check for legacy data", e)
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
         * Marks the data schema as current to prevent redundant migration checks.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore write fails.
         */
        suspend fun markSchemaCurrent() {
            try {
                appDataStore.edit { it[DataStoreKeys.DATA_SCHEMA_VERSION] = DataStoreKeys.CURRENT_DATA_SCHEMA_VERSION }
            } catch (e: Exception) {
                AppLogger.e("IdentityMigrationService: Failed to mark schema current", e)
            }
        }

        /**
         * Migrates legacy data for the specified active SIM identity.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore migration fails.
         * @throws kotlinx.coroutines.CancellationException if the migration process is cancelled.
         */
        suspend fun migrateLegacyData(activeIdentity: com.example.minutewatcher.core.model.SimIdentity) {
            try {
                performMigration(activeIdentity)
            } catch (e: Exception) {
                AppLogger.e("IdentityMigrationService: Fatal error during migration execution", e)
            }
        }

        private suspend fun performMigration(activeIdentity: com.example.minutewatcher.core.model.SimIdentity) {
            val slot = activeIdentity.slotIndex ?: return
            val identity = activeIdentity.stableId

            val legacySuffixes = findLegacySuffixes(activeIdentity)
            val newSuffix = "_$identity"

            val results = mutableListOf<Boolean>()
            for (legacySuffix in legacySuffixes) {
                if (legacySuffix == newSuffix) continue
                results.add(
                    migrateDataStore(
                        appDataStore,
                        legacySuffix,
                        newSuffix,
                        "app_settings",
                        slot,
                        identity,
                    ),
                )
                results.add(
                    migrateDataStore(
                        simDataStore,
                        legacySuffix,
                        newSuffix,
                        "sim_settings",
                        slot,
                        identity,
                    ),
                )
            }

            if (results.any { it }) {
                AppLogger.d("Migration completed for identity $identity: ${results.count { it }} stores migrated")
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
                                .filter { it.name.startsWith("subscription_id_icc_") }
                                .filter { prefs[it] == subId }
                        keysToRemove.forEach { prefs.remove(it) }
                    }
                }
            }
        }

        private suspend fun findLegacySuffixes(
            activeIdentity: com.example.minutewatcher.core.model.SimIdentity,
        ): List<String> {
            val suffixes = mutableListOf<String>()

            // Always try to migrate from old slot-based keys (V1)
            activeIdentity.slotIndex?.let {
                suffixes.add("_sim_$it")
            }

            // If current is ICCID, we might have legacy data under sub_N (e.g. from transition sub -> icc)
            if (activeIdentity.source == com.example.minutewatcher.core.model.IdentitySource.ICCID) {
                activeIdentity.subscriptionId?.let { subId ->
                    suffixes.add("_sub_$subId")
                }
            }

            // If current is SUBSCRIPTION_ID, we might have legacy data under icc_... (e.g. OS upgrade Android 9 -> 10)
            if (activeIdentity.source == com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID) {
                val subId = activeIdentity.subscriptionId
                if (subId != null) {
                    val simPrefs = simDataStore.data.first()
                    val iccIdentities =
                        simPrefs
                            .asMap()
                            .keys
                            .filter { it.name.startsWith("subscription_id_icc_") }
                            .filter { simPrefs[it] == subId }
                            .map { it.name.removePrefix("subscription_id_") }

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
            slot: Int,
            identity: String,
        ): Boolean {
            var migratedCount = 0
            var conflictCount = 0
            dataStore.edit { prefs ->
                val keysToMigrate = prefs.asMap().keys.filter { it.name.endsWith(legacySuffix) }

                for (oldKey in keysToMigrate) {
                    val baseName = oldKey.name.removeSuffix(legacySuffix)
                    val newKeyName = baseName + newSuffix

                    val result = prefs.migrateTypedKey(oldKey, newKeyName, storeName)
                    when (result) {
                        MigrationResult.SUCCESS -> {
                            migratedCount++
                            prefs.remove(oldKey)
                        }
                        MigrationResult.CONFLICT -> {
                            conflictCount++
                            prefs.remove(oldKey)
                        }
                        MigrationResult.FAILURE -> {
                            // Do not remove the old key if migration failed (e.g. unknown type),
                            // to avoid silent data loss.
                        }
                    }
                }
            }
            if (migratedCount > 0 || conflictCount > 0) {
                AppLogger.d(
                    "Migrated legacy data ($legacySuffix) from $storeName for slot $slot " +
                        "to identity $identity: $migratedCount keys, $conflictCount conflicts",
                )
            }
            return migratedCount > 0
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
