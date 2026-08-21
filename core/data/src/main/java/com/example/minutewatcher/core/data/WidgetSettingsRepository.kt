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
         * Uses [Constants.Flow.LONG_TIMEOUT_MS] for DataStore operations.
         * Safe to call from any coroutine context.
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
         * **Частичный успех (Partial Success):**
         * Если при сохранении SIM-специфичных настроек происходит [SeedUnavailableException]
         * или [SimNotFoundException], этот слот пропускается, и сохранение продолжается
         * для остальных. Метод возвращает [WidgetSaveResult.PartialSuccess],
         * если общие настройки сохранены, но хотя бы один слот SIM не удалось сохранить.
         * Это поведение позволяет избежать блокировки сохранения всех настроек из-за деградации DataStore
         * (seed unavailable) на некоторых кастомных прошивках.
         *
         * @param state The state to save.
         * @param skipSimSettings Если true, SIM-специфичные настройки НЕ сохраняются.
         *   Используется при simListLoadFailed = true, когда список SIM недоступен.
         *   Общие настройки (layout, transparency, fontSize) сохраняются всегда.
         *
         *   Строгое соблюдение API: на AOSP список SIM доступен всегда.
         *   simListLoadFailed = true возможен только при деградации DataStore
         *   на кастомной прошивке. Это проблема кастомной прошивки.
         * @return [WidgetSaveResult] indicating full or partial success.
         * @throws Exception if general settings fail to save.
         */
        override suspend fun save(
            state: WidgetSettingsState,
            skipSimSettings: Boolean,
        ): WidgetSaveResult {
            var generalSaved = false
            if (!state.settingsLoadFailed) {
                saveGeneralSettings(state)
                generalSaved = true
            } else {
                AppLogger.w("save: general settings were not loaded. Saving only SIM-specific settings.")
            }

            if (state.simListLoadFailed || skipSimSettings) {
                val label = if (state.settingsLoadFailed) "all saves" else "SIM-specific settings save"
                val reason = if (skipSimSettings) "skipSimSettings=true" else "SIM list was not loaded successfully"
                AppLogger.w("save: $reason. Skipping $label.")
                return if (state.simListLoadFailed && generalSaved) {
                    WidgetSaveResult.PartialSuccess
                } else {
                    WidgetSaveResult.Success
                }
            } else {
                val simSuccess = saveSimSettings(state)
                return if (simSuccess) {
                    WidgetSaveResult.Success
                } else {
                    WidgetSaveResult.PartialSuccess
                }
            }
        }

        /**
         * Saves general widget settings (layout, transparency, etc.) to DataStore.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
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
         * Safe to call from any coroutine context.
         * @return True if all active SIM settings were saved successfully.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun saveSimSettings(state: WidgetSettingsState): Boolean {
            val simSettingsMap = mutableMapOf<String, SimSettingsState>()
            var allSucceeded = true
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
                    allSucceeded = false
                } catch (e: SimNotFoundException) {
                    AppLogger.w("saveSimSettings: SIM not found in slot $slot. Skipping this SIM.", e)
                    allSucceeded = false
                }
            }
            if (simSettingsMap.isNotEmpty()) {
                simWidgetVisibilityRepository.setSimSettingsBatch(simSettingsMap)
            } else if (state.simStates.isNotEmpty()) {
                allSucceeded = false
            }
            return allSucceeded
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
