package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
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

                val activeSims =
                    simIdentityRepository
                        .observeActiveSims()
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                if (activeSims == null) {
                    AppLogger.e("WidgetSettingsRepository: failed to load active SIMs")
                    return@coroutineScope getFallbackWidgetSettings(settings)
                }

                // Use the captured activeSims to load settings.
                // While the list of active SIMs could theoretically change between these calls,
                // we maintain consistency by mapping the results back to the identities we just identified.
                // Eventual consistency is acceptable for widget settings UI.
                val realIdentities = activeSims.map { it.identity.stableId }
                val fallbackIdentities = activeSims.map { "sim_${it.slotIndex}" }
                val allIdentities = (realIdentities + fallbackIdentities).toSet().toList()

                val batchSettingsResult =
                    simWidgetVisibilityRepository
                        .getSimSettingsBatch(allIdentities)
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                if (batchSettingsResult == null) {
                    AppLogger.e("WidgetSettingsRepository: failed to load SIM visibility settings after timeout")
                }
                val batchSettings = batchSettingsResult ?: emptyMap()

                // Map results back to the activeSims list we started with to ensure
                // the returned state only contains data for SIMs that were active at the start of the call.
                val simStates =
                    activeSims.associate { info ->
                        val identity = info.identity.stableId
                        val fallbackKey = "sim_${info.slotIndex}"

                        // Use default settings if specific settings for this identity are missing.
                        val settings =
                            if (identity == fallbackKey) {
                                batchSettings[identity] ?: SimSettingsState()
                            } else {
                                batchSettings[identity] ?: batchSettings[fallbackKey] ?: SimSettingsState()
                            }
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
                    settingsLoadFailed = batchSettingsResult == null,
                )
            }

        override suspend fun save(state: WidgetSettingsState) {
            if (state.settingsLoadFailed) {
                AppLogger.w("Skipping save: settings were not loaded successfully")
                return
            }

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

        private fun getFallbackWidgetSettings(settings: AppSettings): WidgetSettingsState {
            val fallbackSimStates = SimConstants.SUPPORTED_SLOT_INDICES.associateWith { SimSettingsState() }
            return WidgetSettingsState(
                layoutMode = settings.widgetLayoutMode,
                simArrangement = settings.widgetSimArrangement,
                transparency = settings.widgetTransparency,
                fontSize = settings.widgetFontSize,
                backgroundColor = settings.widgetBackgroundColor,
                notifyLowMinutes = settings.notifyLowMinutes,
                notifyLowTraffic = settings.notifyLowTraffic,
                callTrackingEnabled = settings.callTrackingEnabled,
                simStates = fallbackSimStates,
                widgetLimitExceeded = settings.widgetLimitExceeded,
                settingsLoadFailed = true,
            )
        }
    }
