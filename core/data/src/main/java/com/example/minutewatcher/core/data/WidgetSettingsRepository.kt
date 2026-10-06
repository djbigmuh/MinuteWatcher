package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.InvalidSlotException
import com.example.minutewatcher.core.domain.SeedUnavailableException
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimNotFoundException
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

/**
 * Repository for managing unified widget settings, aggregating app and SIM settings.
 *
 * Implementation of [WidgetSettingsLoader].
 * Safe to call from any coroutine context.
 */
@Singleton
class WidgetSettingsRepository
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val simIdentityRepository: SimIdentityRepository,
        private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository,
    ) : WidgetSettingsLoader {
        /**
         * Loads the current unified widget settings state.
         *
         * Aggregates general app settings and SIM-specific visibility settings for currently active SIMs.
         * Uses [Constants.Flow.LONG_TIMEOUT_MS] for DataStore operations.
         *
         * @return [WidgetSettingsState] containing the aggregated widget settings.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * @throws kotlinx.coroutines.CancellationException if the operation is cancelled.
         */
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
                    // If no SIMs found (timeout),
                    // return fallback to avoid infinite loops.
                    return@coroutineScope getFallbackWidgetSettings(
                        settings,
                        settingsLoadFailed = false,
                        simListLoadFailed = true,
                    )
                }

                val simStates =
                    loadSimStatesInternal(activeSims)
                        ?: return@coroutineScope getFallbackWidgetSettings(
                            settings,
                            settingsLoadFailed = false,
                            simListLoadFailed = true,
                        )

                WidgetSettingsState(
                    layoutMode = settings.widgetLayoutMode,
                    simArrangement = settings.widgetSimArrangement,
                    transparency = settings.widgetTransparency,
                    fontSize = settings.widgetFontSize,
                    backgroundColor = settings.widgetBackgroundColor,
                    notifyLowMinutes = settings.notifyLowMinutes,
                    notifyLowTraffic = settings.notifyLowTraffic,
                    aodSummaryEnabled = settings.aodSummaryEnabled,
                    simStates = simStates,
                    widgetLimitExceeded = settings.widgetLimitExceeded,
                    settingsLoadFailed = false,
                    simListLoadFailed = false,
                )
            }

        private suspend fun loadSimStatesInternal(activeSims: List<ActiveSimInfo>): Map<Int, SimSettingsState>? {
            val allIdentities = activeSims.map { it.identity.stableId }

            val batchSettings =
                if (activeSims.isEmpty()) {
                    emptyMap()
                } else {
                    simWidgetVisibilityRepository
                        .getSimSettingsBatch(allIdentities)
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                }

            return batchSettings?.let { batch ->
                activeSims.associate { info ->
                    val identity = info.identity.stableId
                    val settings = batch[identity]

                    if (settings == null) {
                        AppLogger.w(
                            "WidgetSettingsRepository: settings not found for slot " +
                                "${info.slotIndex} (identity: $identity)",
                        )
                    }

                    info.slotIndex to (settings ?: SimSettingsState()).copy(lastKnownIdentity = identity)
                }
            }
        }

        /**
         * Saves general widget settings (layout, transparency, etc.) to DataStore.
         *
         * @param state The state containing general widget settings to save.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore write fails.
         * @throws kotlinx.coroutines.CancellationException if the operation is cancelled.
         */
        override suspend fun saveGeneralSettings(state: WidgetSettingsState) {
            appSettingsRepository.updateAppSettings { current ->
                current.copy(
                    widgetLayoutMode = state.layoutMode,
                    widgetSimArrangement = state.simArrangement,
                    widgetTransparency = state.transparency,
                    widgetFontSize = state.fontSize,
                    widgetBackgroundColor = state.backgroundColor,
                    notifyLowMinutes = state.notifyLowMinutes,
                    notifyLowTraffic = state.notifyLowTraffic,
                    aodSummaryEnabled = state.aodSummaryEnabled,
                )
            }
        }

        /**
         * Saves SIM-specific visibility settings to DataStore.
         *
         * Resolves SIM identities for all provided SIM states and updates the visibility settings in batch.
         *
         * @param state The state containing SIM-specific settings to save.
         * @return List of slot indices that failed to save due to identity resolution errors.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore write fails.
         * @throws kotlinx.coroutines.CancellationException if the operation is cancelled.
         */
        override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> {
            val simSettingsMap = mutableMapOf<String, SimSettingsState>()
            val failedSlots = mutableListOf<Int>()
            for ((slot, simSettings) in state.simStates) {
                mapSlotToSettings(slot, simSettings, simSettingsMap, failedSlots)
            }
            if (simSettingsMap.isNotEmpty()) {
                simWidgetVisibilityRepository.setSimSettingsBatch(simSettingsMap)
            } else if (state.simStates.isNotEmpty() && failedSlots.isEmpty()) {
                // If we have states to save but none could be mapped to an identity (and no exceptions were caught)
                // this is also a failure.
                failedSlots.addAll(state.simStates.keys)
            }
            return failedSlots
        }

        /**
         * Maps a slot index to its settings using the SIM identity.
         *
         * **Attribution logic:**
         * Widget settings are attributed by SIM identity. Slot-scoped keys are avoided
         * to prevent legacy data migration from incorrectly transferring settings
         * between different SIM cards in the same slot.
         */
        private suspend fun mapSlotToSettings(
            slot: Int,
            simSettings: SimSettingsState,
            simSettingsMap: MutableMap<String, SimSettingsState>,
            failedSlots: MutableList<Int>,
        ) {
            try {
                val identity = simIdentityRepository.getIdentity(slot)
                simSettingsMap[identity] = simSettings
            } catch (e: SeedUnavailableException) {
                AppLogger.w(
                    "saveSimSettings: seed unavailable for slot $slot. " +
                        "Saving with last known identity if available.",
                    e,
                )
                simSettings.lastKnownIdentity?.let { lastId ->
                    simSettingsMap[lastId] = simSettings
                } ?: failedSlots.add(slot)
            } catch (e: SimNotFoundException) {
                AppLogger.w(
                    "saveSimSettings: SIM not found in slot $slot. Saving with last known identity if available.",
                    e,
                )
                simSettings.lastKnownIdentity?.let { lastId ->
                    simSettingsMap[lastId] = simSettings
                } ?: failedSlots.add(slot)
            } catch (e: InvalidSlotException) {
                AppLogger.w("saveSimSettings: invalid slot $slot. Skipping.", e)
                failedSlots.add(slot)
            }
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
                aodSummaryEnabled = settings.aodSummaryEnabled,
                simStates = emptyMap(),
                widgetLimitExceeded = settings.widgetLimitExceeded,
                settingsLoadFailed = settingsLoadFailed,
                simListLoadFailed = simListLoadFailed,
            )
    }
