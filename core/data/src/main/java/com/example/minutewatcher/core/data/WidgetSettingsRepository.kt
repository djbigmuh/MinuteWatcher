package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SeedUnavailableException
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimNotFoundException
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.domain.WidgetSaveResult
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private val saveMutex = Mutex()

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
                    AppLogger.e("WidgetSettingsRepository: failed to load active SIMs")
                    return@coroutineScope getFallbackWidgetSettings(
                        settings,
                        settingsLoadFailed = false,
                        simListLoadFailed = true,
                    )
                }

                val simStates = loadSimStatesInternal(activeSims)
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
         * Обеспечивает атомарность на уровне репозитория с использованием [saveMutex].
         * Операции выполняются в строгом порядке: сначала сохраняются SIM-настройки,
         * затем общие настройки. Это гарантирует согласованность при сбоях в "хрупких"
         * частях DataStore (связанных с SIM identity).
         *
         * @param state The state to save.
         * @param skipSimSettings Если true, SIM-специфичные настройки НЕ сохраняются.
         * @return [WidgetSaveResult] indicating full or partial success.
         *
         * **Thread Safety:** Safe to call from any coroutine context. Sequential execution is enforced via [saveMutex].
         * @throws kotlinx.coroutines.CancellationException if the operation is cancelled.
         */
        override suspend fun save(
            state: WidgetSettingsState,
            skipSimSettings: Boolean,
        ): WidgetSaveResult =
            saveMutex.withLock {
                try {
                    // 1. Сохраняем SIM-настройки (более хрупкая операция).
                    // Если она выбросит исключение, общие настройки не будут затронуты.
                    val failedSlots =
                        if (state.simListLoadFailed || skipSimSettings) {
                            emptyList()
                        } else {
                            saveSimSettings(state)
                        }

                    // 2. Сохраняем общие настройки.
                    var generalSaved = false
                    if (!state.settingsLoadFailed) {
                        saveGeneralSettings(state)
                        generalSaved = true
                    } else {
                        AppLogger.w("save: general settings were not loaded. Skipping general settings save.")
                    }

                    // 3. Формируем результат на основе успеха обеих операций.
                    return when {
                        failedSlots.isNotEmpty() -> WidgetSaveResult.PartialSuccess
                        generalSaved && state.simListLoadFailed -> WidgetSaveResult.PartialSuccess
                        generalSaved -> WidgetSaveResult.Success
                        else -> WidgetSaveResult.Failed
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("WidgetSettingsRepository: failed to save widget settings", e)
                    WidgetSaveResult.Failed
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
                try {
                    val identity = simIdentityRepository.getIdentity(slot)
                    simSettingsMap[identity] = simSettings
                    val fallbackKey = "sim_$slot"
                    if (identity != fallbackKey) {
                        simSettingsMap[fallbackKey] = simSettings
                    }
                } catch (e: SeedUnavailableException) {
                    AppLogger.w("saveSimSettings: seed unavailable for slot $slot. Skipping this SIM.", e)
                    failedSlots.add(slot)
                } catch (e: SimNotFoundException) {
                    AppLogger.w("saveSimSettings: SIM not found in slot $slot. Skipping this SIM.", e)
                    failedSlots.add(slot)
                }
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
