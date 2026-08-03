package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        private val dataChangeNotifier: DataChangeNotifier,
    ) : WidgetSettingsLoader {
        override suspend fun load(): WidgetSettingsState =
            coroutineScope {
                val settings = appSettingsRepository.getAppSettings().first()

                val simIdentities =
                    SimConstants.SUPPORTED_SLOT_INDICES
                        .map { slot ->
                            async { simIdentityRepository.getSimIdentity(slot) }
                        }.awaitAll()
                val identities = simIdentities.map { it.stableId }
                val batchSettings = simWidgetVisibilityRepository.getSimSettingsBatch(identities).first()

                val simStates =
                    simIdentities.associate { identity ->
                        (identity.slotIndex ?: 0) to (batchSettings[identity.stableId] ?: SimSettingsState())
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
                if (slot !in SimConstants.SUPPORTED_SLOT_INDICES) continue
                val identity = simIdentityRepository.getIdentity(slot)
                simSettingsMap[identity] = simSettings
            }
            simWidgetVisibilityRepository.setSimSettingsBatch(simSettingsMap)
            dataChangeNotifier.notifyDataChanged(updateWidget = true)
        }
    }
