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
         * Returns a flow of a map from identity to its [SimSettingsState] for a list of identities.
         *
         * Safe to call from any coroutine context.
         */
        override fun getSimSettingsBatch(identities: List<String>): Flow<Map<String, SimSettingsState>> =
            dataStore.data.map { prefs ->
                identities
                    .filter { !it.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX) }
                    .mapNotNull { identity ->
                        val showSim = prefs[DataStoreKeys.showSimKey(identity)]
                        if (showSim == null) return@mapNotNull null

                        identity to
                            SimSettingsState(
                                showSim = showSim,
                                showIcon = prefs[DataStoreKeys.showSimIconKey(identity)] ?: true,
                                showName = prefs[DataStoreKeys.showSimNameKey(identity)] ?: true,
                                showMinutes = prefs[DataStoreKeys.showMinutesKey(identity)] ?: true,
                                showMinutesLimit = prefs[DataStoreKeys.showMinutesLimitKey(identity)] ?: false,
                                showTraffic = prefs[DataStoreKeys.showTrafficKey(identity)] ?: true,
                                showTrafficLimit = prefs[DataStoreKeys.showTrafficLimitKey(identity)] ?: false,
                            )
                    }.toMap()
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
         * **Критичность порядка обхода префиксов:**
         * [DataStoreKeys.ALL_WIDGET_PREFIXES] отсортированы по убыванию длины.
         * Это гарантирует, что более специфичные префиксы проверяются первыми:
         * - `widget_show_sim_icon_` проверяется ПЕРЕД `widget_show_sim_`
         * - `widget_show_minutes_limit_` проверяется ПЕРЕД `widget_show_minutes_`
         *
         * Без сортировки ключ `widget_show_sim_icon_icc_abc` мог бы быть
         * ошибочно обработан префиксом `widget_show_sim_`, что дало бы
         * identity = `icon_icc_abc` вместо корректного `icc_abc`.
         *
         * **Валидация identity:** после извлечения проверяется, что identity
         * начинается с одного из допустимых префиксов (`icc_`, `sub_`, `sim_`).
         * Это защищает от повреждения данных и некорректных ключей.
         * На AOSP все ключи генерируются через DataStoreKeys и всегда валидны.
         * Невалидный identity возможен только при повреждении DataStore
         * на кастомной прошивке. Это проблема кастомной прошивки.
         *
         * @param keyName Полное имя ключа в DataStore.
         * @return Извлечённый identity или null, если ключ не относится к настройкам виджета
         *         или identity имеет недопустимый формат.
         */
        @Suppress("ReturnCount")
        // Подавление ReturnCount оправдано: метод реализует линейную логику извлечения и
        // валидации identity с ранними выходами при несовпадении форматов.
        private fun extractIdentity(keyName: String): String? {
            if (!keyName.startsWith("widget_show_")) return null

            val prefixes = DataStoreKeys.ALL_WIDGET_PREFIXES
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
                            // Проверка на fallback-ключи: identity начинается с "sim_".
                            // На AOSP все fallback-ключи имеют формат "sim_<число>".
                            // Если identity не начинается с допустимого префикса (icc_, sub_, sim_),
                            // он считается невалидным и возвращается null.
                            // Отклонение кастомной прошивки: если прошивка генерирует
                            // нестандартные форматы identity — это проблема кастомной прошивки.
                            identity.startsWith("sim_")

                    return if (isValid) identity else null
                }
            }
            return null
        }
    }
