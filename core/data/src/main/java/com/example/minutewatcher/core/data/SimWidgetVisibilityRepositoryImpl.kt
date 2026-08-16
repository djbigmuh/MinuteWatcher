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
            val fallbackRegex = Regex("^sim_\\d+$")

            dataStore.edit { prefs ->
                val allKeys = prefs.asMap().keys
                val orphanedKeys =
                    allKeys.filter { key ->
                        val identity = extractIdentity(key.name) ?: return@filter false

                        // Protect sim_$slot fallback keys
                        val isFallbackKey = fallbackRegex.matches(identity)

                        identity !in activeSet && !isFallbackKey
                    }
                if (orphanedKeys.isNotEmpty()) {
                    AppLogger.d("Clearing ${orphanedKeys.size} orphaned widget settings keys")
                    orphanedKeys.forEach { prefs.remove(it) }
                }
            }
        }

        /**
         * Извлекает identity из имени ключа DataStore.
         *
         * Допустимые форматы identity:
         * - `icc_<hash>` (стабильный идентификатор на базе ICCID)
         * - `sub_<id>` (идентификатор подписки)
         * - `sim_<slot>` (fallback-идентификатор на базе индекса слота)
         *
         * Извлечение через [String.removePrefix] корректно только при соблюдении инварианта:
         * identity не может начинаться с подстроки, совпадающей с суффиксом любого префикса ключа
         * (например, identity не может начинаться с `icon_`, `name_`, `minutes_` и т.д.).
         *
         * Валидация через допустимые префиксы (`icc_`, `sub_`, `sim_`) защищает от нарушения
         * этого инварианта, отсекая некорректно извлеченные identity.
         *
         * Если кастомная прошивка возвращает identity в недопустимом формате — это проблема
         * кастомной прошивки.
         *
         * @param keyName Полное имя ключа в DataStore.
         * @return Извлеченный identity или `null`, если ключ не относится к настройкам виджета
         *         или identity имеет недопустимый формат.
         */
        @Suppress("ReturnCount")
        // Подавление ReturnCount оправдано: метод реализует линейную логику извлечения и
        // валидации identity с ранними выходами при несовпадении форматов.
        private fun extractIdentity(keyName: String): String? {
            if (!keyName.startsWith("widget_show_")) return null

            val prefixes = WidgetSettingsKeys.ALL_PREFIXES
            for (prefix in prefixes) {
                if (keyName.startsWith(prefix)) {
                    val identity = keyName.removePrefix(prefix)
                    // Защита от ключей, состоящих только из префикса (пустой identity).
                    // Такие ключи не должны существовать в нормальных условиях,
                    // но защита обеспечивает устойчивость к повреждению данных.
                    if (identity.isEmpty()) return null

                    // Валидация: identity должен начинаться с одного из допустимых префиксов.
                    // Это защищает от ситуаций, когда identity (например, "icon_foo") ошибочно
                    // интерпретируется как "foo" из-за совпадения с суффиксом префикса
                    // "widget_show_sim_icon_".
                    val isValid =
                        identity.startsWith("icc_") ||
                            identity.startsWith("sub_") ||
                            identity.startsWith("sim_")

                    return if (isValid) identity else null
                }
            }
            return null
        }
    }
