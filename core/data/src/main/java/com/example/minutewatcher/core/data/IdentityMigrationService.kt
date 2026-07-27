package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.model.util.AppLogger
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
        suspend fun hasLegacyData(): Boolean {
            val appHasLegacy =
                appDataStore.data
                    .first()
                    .asMap()
                    .keys
                    .any { it.name.endsWith("_sim_0") || it.name.endsWith("_sim_1") }
            val simHasLegacy =
                simDataStore.data
                    .first()
                    .asMap()
                    .keys
                    .any { it.name.endsWith("_sim_0") || it.name.endsWith("_sim_1") }
            return appHasLegacy || simHasLegacy
        }

        suspend fun migrateLegacyData(
            slot: Int,
            identity: String,
        ) {
            if (identity == "sim_$slot") return

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
        ): Boolean {
            var anyMigrated = false
            dataStore.edit { prefs ->
                val keysToMigrate = prefs.asMap().keys.filter { it.name.endsWith(legacySuffix) }

                for (oldKey in keysToMigrate) {
                    val value = prefs[oldKey] ?: continue
                    val baseName = oldKey.name.substringBefore(legacySuffix)
                    val newKeyName = baseName + newSuffix

                    // Only migrate if the new key doesn't exist yet
                    // To avoid overwriting already established identity-based settings
                    val keyHandled =
                        when (value) {
                            is Int -> {
                                val newKey = intPreferencesKey(newKeyName)
                                if (prefs[newKey] == null) {
                                    prefs[newKey] = value
                                    true
                                } else {
                                    false
                                }
                            }
                            is Float -> {
                                val newKey = floatPreferencesKey(newKeyName)
                                if (prefs[newKey] == null) {
                                    prefs[newKey] = value
                                    true
                                } else {
                                    false
                                }
                            }
                            is Long -> {
                                val newKey = longPreferencesKey(newKeyName)
                                if (prefs[newKey] == null) {
                                    prefs[newKey] = value
                                    true
                                } else {
                                    false
                                }
                            }
                            is Boolean -> {
                                val newKey = booleanPreferencesKey(newKeyName)
                                if (prefs[newKey] == null) {
                                    prefs[newKey] = value
                                    true
                                } else {
                                    false
                                }
                            }
                            is String -> {
                                val newKey = stringPreferencesKey(newKeyName)
                                if (prefs[newKey] == null) {
                                    prefs[newKey] = value
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }

                    if (keyHandled) anyMigrated = true
                    prefs.remove(oldKey)
                }
            }
            if (anyMigrated) {
                AppLogger.d("Migrated legacy data from $storeName for slot $slot to identity $identity")
            }
            return anyMigrated
        }
    }
