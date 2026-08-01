package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
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
        override fun getSimSettings(identity: String): Flow<SimSettingsState> =
            dataStore.data.map { prefs ->
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

        override fun getSimSettingsBatch(identities: List<String>): Flow<Map<String, SimSettingsState>> =
            dataStore.data.map { prefs ->
                identities.associateWith { identity ->
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

        override suspend fun setSimSettingsBatch(settingsMap: Map<String, SimSettingsState>) {
            dataStore.edit { prefs ->
                settingsMap.forEach { (identity, settings) ->
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
    }
