package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
                val settings = appSettingsRepository.getAppSettings().first()

                val activeSims = simIdentityRepository.observeActiveSims().firstWithTimeout() ?: emptyList()
                val realIdentities = activeSims.map { it.identity.stableId }
                val fallbackIdentities = activeSims.map { "sim_${it.slotIndex}" }
                val allIdentities = (realIdentities + fallbackIdentities).distinct()

                val batchSettings = simWidgetVisibilityRepository.getSimSettingsBatch(allIdentities).first()

                val simStates =
                    activeSims.associate { info ->
                        val identity = info.identity.stableId
                        val fallbackKey = "sim_${info.slotIndex}"
                        val settings = batchSettings[identity] ?: batchSettings[fallbackKey] ?: SimSettingsState()
                        info.slotIndex to settings
                    }

                WidgetSettingsState(
                    layoutMode = settings.widgetLayoutMode,
                    simArrangement = settings.widgetSimArrangement,
                    transparency = settings.widgetTransparency,
                    fontSize = settings.widgetFontSize,
                    backgroundColor = settings.widgetBackgroundColor,
                    notifyLowMinutes = settings.notifyLowMinutes,
                    notifyLowTraffic = settings.notifyLowTraffic,
                    callTrackingEnabled = settings.callTrackingEnabled,
                    simStates = simStates,
                    widgetLimitExceeded = settings.widgetLimitExceeded,
                )
            }

        override suspend fun save(state: WidgetSettingsState) {
            appSettingsRepository.updateAppSettings { current ->
                current.copy(
                    widgetLayoutMode = state.layoutMode,
                    widgetSimArrangement = state.simArrangement,
                    widgetTransparency = state.transparency,
                    widgetFontSize = state.fontSize,
                    widgetBackgroundColor = state.backgroundColor,
                    notifyLowMinutes = state.notifyLowMinutes,
                    notifyLowTraffic = state.notifyLowTraffic,
                    callTrackingEnabled = state.callTrackingEnabled,
                )
            }

            val simSettingsMap = mutableMapOf<String, SimSettingsState>()
            for ((slot, simSettings) in state.simStates) {
                val identity = simIdentityRepository.getIdentity(slot)
                simSettingsMap[identity] = simSettings

                // Also save under fallback-key for persistence across SIM changes and when SIM is absent
                val fallbackKey = "sim_$slot"
                if (identity != fallbackKey) {
                    simSettingsMap[fallbackKey] = simSettings
                }
            }
            simWidgetVisibilityRepository.setSimSettingsBatch(simSettingsMap)
        }
    }
