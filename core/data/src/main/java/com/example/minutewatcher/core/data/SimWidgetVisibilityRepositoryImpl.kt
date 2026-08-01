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
                    showSim = prefs[SettingsKeys.showSimKey(identity)] ?: true,
                    showIcon = prefs[SettingsKeys.showSimIconKey(identity)] ?: true,
                    showName = prefs[SettingsKeys.showSimNameKey(identity)] ?: true,
                    showMinutes = prefs[SettingsKeys.showMinutesKey(identity)] ?: true,
                    showMinutesLimit = prefs[SettingsKeys.showMinutesLimitKey(identity)] ?: false,
                    showTraffic = prefs[SettingsKeys.showTrafficKey(identity)] ?: true,
                    showTrafficLimit = prefs[SettingsKeys.showTrafficLimitKey(identity)] ?: false,
                )
            }

        override fun getSimSettingsBatch(identities: List<String>): Flow<Map<String, SimSettingsState>> =
            dataStore.data.map { prefs ->
                identities.associateWith { identity ->
                    SimSettingsState(
                        showSim = prefs[SettingsKeys.showSimKey(identity)] ?: true,
                        showIcon = prefs[SettingsKeys.showSimIconKey(identity)] ?: true,
                        showName = prefs[SettingsKeys.showSimNameKey(identity)] ?: true,
                        showMinutes = prefs[SettingsKeys.showMinutesKey(identity)] ?: true,
                        showMinutesLimit = prefs[SettingsKeys.showMinutesLimitKey(identity)] ?: false,
                        showTraffic = prefs[SettingsKeys.showTrafficKey(identity)] ?: true,
                        showTrafficLimit = prefs[SettingsKeys.showTrafficLimitKey(identity)] ?: false,
                    )
                }
            }

        override suspend fun setSimSettingsBatch(settingsMap: Map<String, SimSettingsState>) {
            dataStore.edit { prefs ->
                settingsMap.forEach { (identity, settings) ->
                    prefs[SettingsKeys.showSimKey(identity)] = settings.showSim
                    prefs[SettingsKeys.showSimIconKey(identity)] = settings.showIcon
                    prefs[SettingsKeys.showSimNameKey(identity)] = settings.showName
                    prefs[SettingsKeys.showMinutesKey(identity)] = settings.showMinutes
                    prefs[SettingsKeys.showMinutesLimitKey(identity)] = settings.showMinutesLimit
                    prefs[SettingsKeys.showTrafficKey(identity)] = settings.showTraffic
                    prefs[SettingsKeys.showTrafficLimitKey(identity)] = settings.showTrafficLimit
                }
            }
        }

        private fun <T> get(
            key: Preferences.Key<T>,
            defaultValue: T,
        ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

        override fun getShowSim(identity: String): Flow<Boolean> = get(SettingsKeys.showSimKey(identity), true)

        override suspend fun setShowSim(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showSimKey(identity)] = show }
        }

        override fun getShowSimIcon(identity: String): Flow<Boolean> = get(SettingsKeys.showSimIconKey(identity), true)

        override suspend fun setShowSimIcon(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showSimIconKey(identity)] = show }
        }

        override fun getShowSimName(identity: String): Flow<Boolean> = get(SettingsKeys.showSimNameKey(identity), true)

        override suspend fun setShowSimName(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showSimNameKey(identity)] = show }
        }

        override fun getShowMinutes(identity: String): Flow<Boolean> = get(SettingsKeys.showMinutesKey(identity), true)

        override suspend fun setShowMinutes(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showMinutesKey(identity)] = show }
        }

        override fun getShowMinutesLimit(identity: String): Flow<Boolean> = get(SettingsKeys.showMinutesLimitKey(identity), false)

        override suspend fun setShowMinutesLimit(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showMinutesLimitKey(identity)] = show }
        }

        override fun getShowTraffic(identity: String): Flow<Boolean> = get(SettingsKeys.showTrafficKey(identity), true)

        override suspend fun setShowTraffic(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showTrafficKey(identity)] = show }
        }

        override fun getShowTrafficLimit(identity: String): Flow<Boolean> = get(SettingsKeys.showTrafficLimitKey(identity), false)

        override suspend fun setShowTrafficLimit(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showTrafficLimitKey(identity)] = show }
        }
    }
