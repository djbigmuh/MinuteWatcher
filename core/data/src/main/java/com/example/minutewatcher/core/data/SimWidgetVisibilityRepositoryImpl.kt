package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.SimDataStore
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.model.SimSettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [SimWidgetVisibilityRepository] using [DataStore].
 *
 * Manages visibility and display settings for SIM components in the app widget.
 * AOSP DataStore compliant.
 * Safe to call from any coroutine context.
 */
@Singleton
class SimWidgetVisibilityRepositoryImpl
    @Inject
    constructor(
        @SimDataStore private val dataStore: DataStore<Preferences>,
    ) : SimWidgetVisibilityRepository {
        // DataStore keys are an implementation detail. The public repository API must not expose them.
        // This complies with the Dependency Inversion Principle.

        /**
         * Returns visibility settings for multiple identities.
         *
         * Missing keys in DataStore will result in [SimSettingsState] with default values.
         * Safe to call from any coroutine context.
         *
         * @param identities List of SIM identities to fetch settings for.
         * @return A flow of a map where keys are SIM identities and values are their visibility settings.
         */
        override fun getSimSettingsBatch(identities: List<String>): Flow<Map<String, SimSettingsState>> =
            dataStore.data.map { prefs ->
                identities
                    .asSequence()
                    .associateWith { identity ->
                        SimSettingsState(
                            showSim = prefs[DataStoreKeys.showSimKey(identity)] ?: true,
                            showIcon = prefs[DataStoreKeys.showSimIconKey(identity)] ?: true,
                            showName = prefs[DataStoreKeys.showSimNameKey(identity)] ?: true,
                            showMinutes = prefs[DataStoreKeys.showMinutesKey(identity)] ?: true,
                            showMinutesLimit = prefs[DataStoreKeys.showMinutesLimitKey(identity)] ?: false,
                            showTraffic = prefs[DataStoreKeys.showTrafficKey(identity)] ?: true,
                            showTrafficLimit = prefs[DataStoreKeys.showTrafficLimitKey(identity)] ?: false,
                        )
                    }
            }

        /**
         * Updates settings for multiple identities in a single DataStore transaction.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setSimSettingsBatch(settingsMap: Map<String, SimSettingsState>) {
            dataStore.edit { prefs ->
                settingsMap.forEach { (identity, settings) ->
                    prefs[DataStoreKeys.showSimKey(identity)] = settings.showSim
                    prefs[DataStoreKeys.showSimIconKey(identity)] = settings.showIcon
                    prefs[DataStoreKeys.showSimNameKey(identity)] = settings.showName
                    prefs[DataStoreKeys.showMinutesKey(identity)] = settings.showMinutes
                    prefs[DataStoreKeys.showMinutesLimitKey(identity)] = settings.showMinutesLimit
                    prefs[DataStoreKeys.showTrafficKey(identity)] = settings.showTraffic
                    prefs[DataStoreKeys.showTrafficLimitKey(identity)] = settings.showTrafficLimit
                }
            }
        }
    }
