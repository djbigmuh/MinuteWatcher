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
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.model.SimConstants
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityMigrationService
    @Inject
    constructor(
        @AppDataStore private val appDataStore: DataStore<Preferences>,
        @SimDataStore private val simDataStore: DataStore<Preferences>,
    ) {
        suspend fun hasLegacyData(activeIdentities: Set<String> = emptySet()): Boolean {
            val appPrefs = appDataStore.data.first()
            if ((appPrefs[SettingsKeys.DATA_SCHEMA_VERSION] ?: 0) >= SettingsKeys.CURRENT_DATA_SCHEMA_VERSION) {
                return false
            }

            val appHasLegacy = appPrefs.asMap().keys.any { it.isLegacyKey(activeIdentities) }
            val simHasLegacy =
                simDataStore.data
                    .first()
                    .asMap()
                    .keys
                    .any { it.isLegacyKey(activeIdentities) }
            return appHasLegacy || simHasLegacy
        }

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

            // Dangerous patterns: only treat as legacy if schema is not current.
            return name.contains("_sub_") || name.contains("_icc_")
        }

        suspend fun markSchemaCurrent() {
            appDataStore.edit { it[SettingsKeys.DATA_SCHEMA_VERSION] = SettingsKeys.CURRENT_DATA_SCHEMA_VERSION }
        }

        suspend fun migrateLegacyData(activeIdentity: com.example.minutewatcher.core.model.SimIdentity) {
            val slot = activeIdentity.slotIndex ?: return
            val identity = activeIdentity.stableId

            val legacySuffixes = findLegacySuffixes(activeIdentity)
            val newSuffix = "_$identity"

            for (legacySuffix in legacySuffixes) {
                if (legacySuffix == newSuffix) continue
                migrateDataStore(appDataStore, legacySuffix, newSuffix, "app_settings", slot, identity)
                migrateDataStore(simDataStore, legacySuffix, newSuffix, "sim_settings", slot, identity)
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

        private suspend fun findLegacySuffixes(activeIdentity: com.example.minutewatcher.core.model.SimIdentity): List<String> {
            val suffixes = mutableListOf<String>()

            // Always try to migrate from old slot-based keys (V1)
            activeIdentity.slotIndex?.let {
                suffixes.add("_sim_$it")
            }

            // If current is ICCID, we might have legacy data under sub_N (e.g. from transition sub -> icc)
            if (activeIdentity.source == com.example.minutewatcher.core.model.IdentitySource.ICCID) {
                activeIdentity.subscriptionId?.let {
                    suffixes.add("_sub_$it")
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
                            .map { it.name.substringAfter("subscription_id_") }

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
            var anyMigrated = false
            dataStore.edit { prefs ->
                anyMigrated = false
                val keysToMigrate = prefs.asMap().keys.filter { it.name.endsWith(legacySuffix) }

                for (oldKey in keysToMigrate) {
                    val baseName = oldKey.name.substringBefore(legacySuffix)
                    val newKeyName = baseName + newSuffix

                    val keyHandled = prefs.migrateTypedKey(oldKey, newKeyName, storeName)

                    if (keyHandled) anyMigrated = true
                    prefs.remove(oldKey)
                }
            }
            if (anyMigrated) {
                AppLogger.d("Migrated legacy data ($legacySuffix) from $storeName for slot $slot to identity $identity")
            }
            return anyMigrated
        }

        private fun MutablePreferences.migrateTypedKey(
            oldKey: Preferences.Key<*>,
            newKeyName: String,
            storeName: String,
        ): Boolean {
            val value = this[oldKey] ?: return false
            val conflictKeyName = "conflict_${oldKey.name}"

            return when (value) {
                is Int -> migrateOrBackup(intPreferencesKey(newKeyName), intPreferencesKey(conflictKeyName), value, storeName)
                is Float -> migrateOrBackup(floatPreferencesKey(newKeyName), floatPreferencesKey(conflictKeyName), value, storeName)
                is Long -> migrateOrBackup(longPreferencesKey(newKeyName), longPreferencesKey(conflictKeyName), value, storeName)
                is Boolean -> migrateOrBackup(booleanPreferencesKey(newKeyName), booleanPreferencesKey(conflictKeyName), value, storeName)
                is String -> migrateOrBackup(stringPreferencesKey(newKeyName), stringPreferencesKey(conflictKeyName), value, storeName)
                else -> {
                    AppLogger.e("Unknown type for key ${oldKey.name}: ${value::class.simpleName}, skipping migration")
                    false
                }
            }
        }

        private fun <T> MutablePreferences.migrateOrBackup(
            newKey: Preferences.Key<T>,
            conflictKey: Preferences.Key<T>,
            value: T,
            storeName: String,
        ): Boolean {
            if (this[newKey] == null) {
                this[newKey] = value
                return true
            } else {
                this[conflictKey] = value
                AppLogger.w(
                    "Migration conflict in $storeName: ${newKey.name} already exists. Legacy value backed up to ${conflictKey.name}",
                )
                return false
            }
        }
    }
