package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.model.Constants.SimConstants
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
        private val prefixMap = DataStoreKeys.ALL_WIDGET_PREFIXES.associateWith { it.length }
        // Ключи DataStore — деталь реализации. Публичный API репозитория не должен их раскрывать.
        // Это соответствует принципу Dependency Inversion.

        /**
         * Returns a flow of [SimSettingsState] for the given identity.
         *
         * Safe to call from any coroutine context.
         */
        override fun getSimSettings(identity: String): Flow<SimSettingsState> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getSimSettings called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(SimSettingsState(showSim = false))
            }
            return dataStore.data.map { prefs ->
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
                    .filter { !it.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX) }
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
                    if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                        AppLogger.w("setSimSettingsBatch skipped for invalid identity: $identity")
                        return@forEach
                    }
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

        /**
         * Очищает настройки для SIM-карт, которые больше не активны.
         *
         * Для предотвращения потери данных fallback-ключи формата `sim_<slot>` никогда не
         * удаляются автоматически, так как они служат последним уровнем защиты при отсутствии
         * стабильных идентификаторов (например, после сброса настроек сети или на старых API).
         *
         * @param activeIdentities Список identity SIM-карт, которые считаются активными в данный момент.
         */
        override suspend fun clearOrphanedSettings(activeIdentities: List<String>) {
            val activeSet = activeIdentities.toSet()
            // Защита fallback-ключей: ключи вида "sim_<число>" никогда не удаляются
            // автоматически. Это последний уровень защиты при отсутствии стабильных
            // идентификаторов (например, после сброса настроек сети или на старых API).
            //
            // Строгое соблюдение API: regex ^sim_\d+$ гарантирует, что только
            // ключи с числовым суффиксом защищаются. Ключи вида "sim_abc"
            // (не числовой суффикс) НЕ защищаются и могут быть удалены как orphaned.
            // На AOSP все fallback-ключи имеют формат "sim_<число>".
            // Отклонение кастомной прошивки: если прошивка генерирует
            // нечисловые суффиксы — это проблема кастомной прошивки.
            val fallbackRegex = Regex("^sim_\\d+$")

            dataStore.edit { prefs ->
                val allKeys = prefs.asMap().keys
                val orphanedKeys =
                    allKeys.filter { key ->
                        val identity = extractIdentity(key.name) ?: return@filter false

                        // Protect sim_$slot fallback keys
                        val isFallbackKey = fallbackRegex.matches(identity)

                        (identity !in activeSet) && !isFallbackKey
                    }
                if (orphanedKeys.isNotEmpty()) {
                    AppLogger.d("Clearing ${orphanedKeys.size} orphaned widget settings keys")
                    orphanedKeys.forEach { prefs.remove(it) }
                }
            }
        }

        /**
         * Extracts identity from a DataStore key name by matching known widget prefixes.
         *
         * **Prefix Matching:** Uses [prefixMap] (ordered by length descending)
         * to ensure the most specific prefix is matched first.
         *
         * **Validation:** Extracted identity must start with a valid prefix (icc_, sub_, sim_)
         * and must not be empty. This prevents data corruption or incorrect parsing.
         *
         * @param keyName Full name of the key in DataStore.
         * @return Extracted identity or null if the key doesn't belong to widget settings or is invalid.
         */
        @Suppress("ReturnCount")
        private fun extractIdentity(keyName: String): String? {
            if (!keyName.startsWith("widget_show_")) return null

            val matchedPrefix = prefixMap.keys.firstOrNull { keyName.startsWith(it) } ?: return null

            return keyName.removePrefix(matchedPrefix).takeIf { identity ->
                identity.isNotEmpty() &&
                    (
                        identity.startsWith("icc_") ||
                            identity.startsWith("sub_") ||
                            identity.startsWith("sim_")
                    )
            }
        }
    }
