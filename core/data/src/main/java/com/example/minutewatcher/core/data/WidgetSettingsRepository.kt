package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetSettingsRepository
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val simRepository: SimRepository,
    ) {
        suspend fun load(): WidgetSettingsState {
            val settings = settingsRepository.getAppSettings().first()

            val simStates =
                SimConstants.SUPPORTED_SLOT_INDICES.map { i ->
                    val identity = simRepository.getIdentity(i)
                    simRepository.getSimSettings(identity).first()
                }
            return WidgetSettingsState(
                layoutMode = settings.widgetLayoutMode,
                simArrangement = settings.widgetSimArrangement,
                transparency = settings.widgetTransparency,
                fontSize = settings.widgetFontSize,
                backgroundColor = settings.widgetBackgroundColor,
                notifyLowMinutes = settings.notifyLowMinutes,
                notifyLowTraffic = settings.notifyLowTraffic,
                callTrackingEnabled = settings.callTrackingEnabled,
                simStates = simStates,
            )
        }

        suspend fun save(state: WidgetSettingsState) {
            settingsRepository.updateAppSettings { current ->
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
            for (i in state.simStates.indices) {
                val identity = simRepository.getIdentity(i)
                simSettingsMap[identity] = state.simStates[i]
            }
            simRepository.setSimSettingsBatch(simSettingsMap)
        }
    }
