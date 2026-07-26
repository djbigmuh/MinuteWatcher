package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.model.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityMigrationService
    @Inject
    constructor(
        @AppDataStore private val appDataStore: DataStore<Preferences>,
        @SimDataStore private val simDataStore: DataStore<Preferences>,
    ) {
        suspend fun migrateLegacyData(
            slot: Int,
            identity: String,
        ) {
            if (identity == "sim_$slot") return

            // We still need to allow migration if called for the first time for this identity
            // But the main migration logic should be guarded globally.
            // Actually, SimRepositoryImpl calls this for each SIM.

            val legacySuffix = "_sim_$slot"
            val newSuffix = "_$identity"

            migrateDataStore(appDataStore, legacySuffix, newSuffix, "app_settings", slot, identity)
            migrateDataStore(simDataStore, legacySuffix, newSuffix, "sim_settings", slot, identity)
        }

        private suspend fun migrateDataStore(
            dataStore: DataStore<Preferences>,
            legacySuffix: String,
            newSuffix: String,
            storeName: String,
            slot: Int,
            identity: String,
        ) {
            dataStore.edit { prefs ->
                val keysToMigrate = prefs.asMap().keys.filter { it.name.endsWith(legacySuffix) }

                var migrated = false
                for (oldKey in keysToMigrate) {
                    val value = prefs[oldKey] ?: continue
                    val baseName = oldKey.name.substringBefore(legacySuffix)
                    val newKeyName = baseName + newSuffix

                    // Only migrate if the new key doesn't exist yet
                    // To avoid overwriting already established identity-based settings
                    when (value) {
                        is Int -> {
                            val newKey = intPreferencesKey(newKeyName)
                            if (prefs[newKey] == null) {
                                prefs[newKey] = value
                                migrated = true
                            }
                        }
                        is Float -> {
                            val newKey = floatPreferencesKey(newKeyName)
                            if (prefs[newKey] == null) {
                                prefs[newKey] = value
                                migrated = true
                            }
                        }
                        is Long -> {
                            val newKey = longPreferencesKey(newKeyName)
                            if (prefs[newKey] == null) {
                                prefs[newKey] = value
                                migrated = true
                            }
                        }
                        is Boolean -> {
                            val newKey = booleanPreferencesKey(newKeyName)
                            if (prefs[newKey] == null) {
                                prefs[newKey] = value
                                migrated = true
                            }
                        }
                        is String -> {
                            val newKey = stringPreferencesKey(newKeyName)
                            if (prefs[newKey] == null) {
                                prefs[newKey] = value
                                migrated = true
                            }
                        }
                    }
                    // We keep the old key for safety for now, or we can remove it.
                    // The audit suggested removal: prefs.remove(oldKey)
                    prefs.remove(oldKey)
                }
                if (migrated) {
                    AppLogger.d("Migrated legacy data from $storeName for slot $slot to identity $identity")
                }
            }
        }
    }
