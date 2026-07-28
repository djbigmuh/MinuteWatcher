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
            activeIdentity: com.example.minutewatcher.core.model.SimIdentity,
        ) {
            val slot = activeIdentity.slotIndex ?: return
            val identity = activeIdentity.stableId

            val legacySuffixes = findLegacySuffixes(activeIdentity)
            val newSuffix = "_$identity"

            for (legacySuffix in legacySuffixes) {
                if (legacySuffix == newSuffix) continue
                migrateDataStore(appDataStore, legacySuffix, newSuffix, "app_settings", slot, identity)
                migrateDataStore(simDataStore, legacySuffix, newSuffix, "sim_settings", slot, identity)
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
                    val iccIdentities =
                        simDataStore.data.first().asMap().keys
                            .filter { it.name.startsWith("subscription_id_icc_") }
                            .filter { simDataStore.data.first()[it] == subId }
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

            return when (value) {
                is Int -> migrateIfAbsent(intPreferencesKey(newKeyName), value, storeName)
                is Float -> migrateIfAbsent(floatPreferencesKey(newKeyName), value, storeName)
                is Long -> migrateIfAbsent(longPreferencesKey(newKeyName), value, storeName)
                is Boolean -> migrateIfAbsent(booleanPreferencesKey(newKeyName), value, storeName)
                is String -> migrateIfAbsent(stringPreferencesKey(newKeyName), value, storeName)
                else -> false
            }
        }

        private fun <T> MutablePreferences.migrateIfAbsent(
            newKey: Preferences.Key<T>,
            value: T,
            storeName: String,
        ): Boolean {
            if (this[newKey] == null) {
                this[newKey] = value
                return true
            } else {
                AppLogger.w("Migration conflict in $storeName: ${newKey.name} already exists. Legacy value skipped.")
                return false
            }
        }
    }
