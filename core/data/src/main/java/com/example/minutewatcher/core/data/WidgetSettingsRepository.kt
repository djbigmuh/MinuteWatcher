package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetSettingsRepository
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val simIdentityRepository: SimIdentityRepository,
        private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository,
    ) : WidgetSettingsLoader {
        override suspend fun load(): WidgetSettingsState =
            coroutineScope {
                val settings =
                    appSettingsRepository
                        .getAppSettings()
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)

                if (settings == null) {
                    AppLogger.e("WidgetSettingsRepository: failed to load app settings")
                    return@coroutineScope getFallbackWidgetSettings(AppSettings())
                }

                val activeSims =
                    simIdentityRepository
                        .observeActiveSims()
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                if (activeSims == null) {
                    AppLogger.e("WidgetSettingsRepository: failed to load active SIMs")
                    return@coroutineScope getFallbackWidgetSettings(
                        settings,
                        settingsLoadFailed = false,
                        simListLoadFailed = true,
                    )
                }

                val simStates = loadSimStatesInternal(activeSims)
                if (simStates == null) {
                    return@coroutineScope getFallbackWidgetSettings(
                        settings,
                        settingsLoadFailed = false,
                        simListLoadFailed = true,
                    )
                }

                WidgetSettingsState(
                    layoutMode = settings.widgetLayoutMode,
                    simArrangement = settings.widgetSimArrangement,
                    transparency = settings.widgetTransparency,
                    fontSize = settings.widgetFontSize,
                    backgroundColor = settings.widgetBackgroundColor,
                    notifyLowMinutes = settings.notifyLowMinutes,
                    notifyLowTraffic = settings.notifyLowTraffic,
                    simStates = simStates,
                    widgetLimitExceeded = settings.widgetLimitExceeded,
                    settingsLoadFailed = false,
                    simListLoadFailed = false,
                )
            }

        private suspend fun loadSimStatesInternal(activeSims: List<ActiveSimInfo>): Map<Int, SimSettingsState>? {
            // Use the captured activeSims to load settings.
            // While the list of active SIMs could theoretically change between these calls,
            // we maintain consistency by mapping the results back to the identities we just identified.
            // Eventual consistency is acceptable for widget settings UI.
            val realIdentities = activeSims.map { it.identity.stableId }
            val fallbackIdentities = activeSims.map { "sim_${it.slotIndex}" }
            val allIdentities = (realIdentities.asSequence() + fallbackIdentities.asSequence()).toSet().toList()

            val batchSettingsResult =
                simWidgetVisibilityRepository
                    .getSimSettingsBatch(allIdentities)
                    .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)

            if (batchSettingsResult == null) {
                AppLogger.e("WidgetSettingsRepository: failed to load SIM visibility settings after timeout")
                return null
            }
            // G-484: Using local variable to satisfy null check and ensure non-null usage below.
            val batchSettings = batchSettingsResult

            // Map results back to the activeSims list we started with to ensure
            // the returned state only contains data for SIMs that were active at the start of the call.
            return activeSims.associate { info ->
                val identity = info.identity.stableId
                val fallbackKey = "sim_${info.slotIndex}"

                // Use default settings if specific settings for this identity are missing.
                val stateSettings =
                    if (identity == fallbackKey) {
                        batchSettings[identity] ?: SimSettingsState()
                    } else {
                        batchSettings[identity] ?: batchSettings[fallbackKey] ?: SimSettingsState()
                    }
                info.slotIndex to stateSettings
            }
        }

        /**
         * Saves the widget settings state to DataStore.
         *
         * Если общие настройки не были загружены (settingsLoadFailed), сохраняются только SIM-специфичные настройки.
         * Это позволяет пользователю сохранить изменения для конкретных SIM даже при сбое загрузки общих настроек.
         * На AOSP загрузка настроек не падает при исправном DataStore; сбой возможен только на кастомных прошивках.
         *
         * @param state The state to save.
         */
        override suspend fun save(state: WidgetSettingsState) {
            if (!state.settingsLoadFailed) {
                appSettingsRepository.updateAppSettings { current ->
                    current.copy(
                        widgetLayoutMode = state.layoutMode,
                        widgetSimArrangement = state.simArrangement,
                        widgetTransparency = state.transparency,
                        widgetFontSize = state.fontSize,
                        widgetBackgroundColor = state.backgroundColor,
                        notifyLowMinutes = state.notifyLowMinutes,
                        notifyLowTraffic = state.notifyLowTraffic,
                    )
                }
            } else {
                // G-484: Общие настройки не были загружены. Сохраняем только то,
                // что было явно изменено пользователем (simStates), но пропускаем
                // общие настройки (layoutMode, transparency и т.д.), чтобы не
                // перезаписать потенциально корректные значения в DataStore
                // значениями по умолчанию из fallback.
                AppLogger.w("save: general settings were not loaded. Saving only SIM-specific settings.")
            }

            if (state.simListLoadFailed) {
                val label = if (state.settingsLoadFailed) "all saves" else "SIM-specific settings save"
                AppLogger.w("save: SIM list was not loaded successfully. Skipping $label.")
            } else {
                saveSimSettings(state)
            }
        }

        private suspend fun saveSimSettings(state: WidgetSettingsState) {
            val simSettingsMap = mutableMapOf<String, SimSettingsState>()
            for ((slot, simSettings) in state.simStates) {
                val identity = simIdentityRepository.getIdentity(slot)
                simSettingsMap[identity] = simSettings
                val fallbackKey = "sim_$slot"
                if (identity != fallbackKey) {
                    simSettingsMap[fallbackKey] = simSettings
                }
            }
            simWidgetVisibilityRepository.setSimSettingsBatch(simSettingsMap)
        }

        private fun getFallbackWidgetSettings(
            settings: AppSettings,
            settingsLoadFailed: Boolean = true,
            simListLoadFailed: Boolean = true,
        ): WidgetSettingsState =
            WidgetSettingsState(
                layoutMode = settings.widgetLayoutMode,
                simArrangement = settings.widgetSimArrangement,
                transparency = settings.widgetTransparency,
                fontSize = settings.widgetFontSize,
                backgroundColor = settings.widgetBackgroundColor,
                notifyLowMinutes = settings.notifyLowMinutes,
                notifyLowTraffic = settings.notifyLowTraffic,
                simStates = emptyMap(),
                widgetLimitExceeded = settings.widgetLimitExceeded,
                settingsLoadFailed = settingsLoadFailed,
                simListLoadFailed = simListLoadFailed,
            )
    }
