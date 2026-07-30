package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
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
        private val appSettingsRepository: AppSettingsRepository,
        private val simIdentityRepository: SimIdentityRepository,
        private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository,
    ) : WidgetSettingsLoader {
        override suspend fun load(): WidgetSettingsState {
            val settings = appSettingsRepository.getAppSettings().first()

            val simStates =
                SimConstants.SUPPORTED_SLOT_INDICES.associateWith { i ->
                    val identity = simIdentityRepository.getIdentity(i)
                    simWidgetVisibilityRepository.getSimSettings(identity).first()
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
            }
            simWidgetVisibilityRepository.setSimSettingsBatch(simSettingsMap)
        }
    }
