package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.MutablePreferences
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
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
                (0..1).map { i ->
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
            settingsRepository.updateSettings { anyPrefs ->
                val prefs = anyPrefs as MutablePreferences
                prefs[SettingsKeys.WIDGET_LAYOUT_MODE] = state.layoutMode
                prefs[SettingsKeys.WIDGET_SIM_ARRANGEMENT] = state.simArrangement
                prefs[SettingsKeys.WIDGET_TRANSPARENCY] = state.transparency
                prefs[SettingsKeys.WIDGET_FONT_SIZE] = state.fontSize
                prefs[SettingsKeys.WIDGET_BACKGROUND_COLOR] = state.backgroundColor
                prefs[SettingsKeys.NOTIFY_LOW_MINUTES] = state.notifyLowMinutes
                prefs[SettingsKeys.NOTIFY_LOW_TRAFFIC] = state.notifyLowTraffic
                prefs[SettingsKeys.CALL_TRACKING_ENABLED] = state.callTrackingEnabled
            }

            for (i in state.simStates.indices) {
                val identity = simRepository.getIdentity(i)
                val sim = state.simStates[i]
                simRepository.setShowSim(identity, sim.showSim)
                simRepository.setShowSimIcon(identity, sim.showIcon)
                simRepository.setShowSimName(identity, sim.showName)
                simRepository.setShowMinutes(identity, sim.showMinutes)
                simRepository.setShowMinutesLimit(identity, sim.showMinutesLimit)
                simRepository.setShowTraffic(identity, sim.showTraffic)
                simRepository.setShowTrafficLimit(identity, sim.showTrafficLimit)
            }
        }
    }
