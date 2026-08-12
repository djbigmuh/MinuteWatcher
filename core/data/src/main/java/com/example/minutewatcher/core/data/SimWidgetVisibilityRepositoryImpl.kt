package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.SimSettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimWidgetVisibilityRepositoryImpl
    @Inject
    constructor(
        @SimDataStore private val dataStore: DataStore<Preferences>,
    ) : SimWidgetVisibilityRepository {
        override fun getSimSettings(identity: String): Flow<SimSettingsState> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getSimSettings called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(SimSettingsState(showSim = false))
            }
            return dataStore.data.map { prefs ->
                SimSettingsState(
                    showSim = prefs[WidgetSettingsKeys.showSimKey(identity)] ?: true,
                    showIcon = prefs[WidgetSettingsKeys.showSimIconKey(identity)] ?: true,
                    showName = prefs[WidgetSettingsKeys.showSimNameKey(identity)] ?: true,
                    showMinutes = prefs[WidgetSettingsKeys.showMinutesKey(identity)] ?: true,
                    showMinutesLimit = prefs[WidgetSettingsKeys.showMinutesLimitKey(identity)] ?: false,
                    showTraffic = prefs[WidgetSettingsKeys.showTrafficKey(identity)] ?: true,
                    showTrafficLimit = prefs[WidgetSettingsKeys.showTrafficLimitKey(identity)] ?: false,
                )
            }
        }

        override fun getSimSettingsBatch(identities: List<String>): Flow<Map<String, SimSettingsState>> =
            dataStore.data.map { prefs ->
                identities
                    .filter { !it.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX) }
                    .mapNotNull { identity ->
                        val showSim = prefs[WidgetSettingsKeys.showSimKey(identity)]
                        if (showSim == null) return@mapNotNull null

                        identity to
                            SimSettingsState(
                                showSim = showSim,
                                showIcon = prefs[WidgetSettingsKeys.showSimIconKey(identity)] ?: true,
                                showName = prefs[WidgetSettingsKeys.showSimNameKey(identity)] ?: true,
                                showMinutes = prefs[WidgetSettingsKeys.showMinutesKey(identity)] ?: true,
                                showMinutesLimit = prefs[WidgetSettingsKeys.showMinutesLimitKey(identity)] ?: false,
                                showTraffic = prefs[WidgetSettingsKeys.showTrafficKey(identity)] ?: true,
                                showTrafficLimit = prefs[WidgetSettingsKeys.showTrafficLimitKey(identity)] ?: false,
                            )
                    }.toMap()
            }

        override suspend fun setSimSettingsBatch(settingsMap: Map<String, SimSettingsState>) {
            dataStore.edit { prefs ->
                settingsMap.forEach { (identity, settings) ->
                    if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                        AppLogger.w("setSimSettingsBatch skipped for invalid identity: $identity")
                        return@forEach
                    }
                    prefs[WidgetSettingsKeys.showSimKey(identity)] = settings.showSim
                    prefs[WidgetSettingsKeys.showSimIconKey(identity)] = settings.showIcon
                    prefs[WidgetSettingsKeys.showSimNameKey(identity)] = settings.showName
                    prefs[WidgetSettingsKeys.showMinutesKey(identity)] = settings.showMinutes
                    prefs[WidgetSettingsKeys.showMinutesLimitKey(identity)] = settings.showMinutesLimit
                    prefs[WidgetSettingsKeys.showTrafficKey(identity)] = settings.showTraffic
                    prefs[WidgetSettingsKeys.showTrafficLimitKey(identity)] = settings.showTrafficLimit
                }
            }
        }

        /**
         * Clears settings for SIMs that are no longer active.
         *
         * **Strict Compliance:**
         * Identity extraction uses strict prefix matching from longest to shortest.
         * Fragile substring methods are avoided to prevent accidental deletion of unrelated keys.
         * Fallback identities (sim_$slot) are protected using a strict regex pattern.
         */
        override suspend fun clearOrphanedSettings(activeIdentities: List<String>) {
            val activeSet = activeIdentities.toSet()
            val prefixes = WidgetSettingsKeys.ALL_PREFIXES
            val fallbackRegex = Regex("^sim_\\d+$")

            dataStore.edit { prefs ->
                val allKeys = prefs.asMap().keys
                val orphanedKeys =
                    allKeys.filter { key ->
                        val name = key.name
                        if (name.startsWith("widget_show_")) {
                            var identity = ""
                            for (prefix in prefixes) {
                                if (name.startsWith(prefix)) {
                                    identity = name.removePrefix(prefix)
                                    break
                                }
                            }

                            if (identity.isEmpty()) return@filter false

                            // Protect sim_$slot fallback keys
                            val isFallbackKey = fallbackRegex.matches(identity)

                            identity !in activeSet && !isFallbackKey
                        } else {
                            false
                        }
                    }
                if (orphanedKeys.isNotEmpty()) {
                    AppLogger.d("Clearing ${orphanedKeys.size} orphaned widget settings keys")
                    orphanedKeys.forEach { prefs.remove(it) }
                }
            }
        }
    }
