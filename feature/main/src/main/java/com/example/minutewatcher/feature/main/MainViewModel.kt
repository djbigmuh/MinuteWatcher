package com.example.minutewatcher.feature.main

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.SnapshotUtils
import com.example.minutewatcher.feature.main.controller.MainViewModelControllers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val controllers: MainViewModelControllers,
        private val appSettingsRepository: AppSettingsRepository,
        private val autoResetUseCase: AutoResetUseCase,
        private val savedStateHandle: SavedStateHandle,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        // Dialog States
        val showLimitDialog = controllers.dialog.showLimitDialog
        val showDayDialog = controllers.dialog.showDayDialog
        val showAdjustDialog = controllers.dialog.showAdjustDialog
        val showTrafficLimitationDialog = controllers.dialog.showTrafficLimitationDialog
        val showUsageAccessDialog = controllers.dialog.showUsageAccessDialog

        fun setLimitDialog(data: LimitDialogData?) = controllers.dialog.setLimitDialog(data)

        fun setDayDialog(data: DayDialogData?) = controllers.dialog.setDayDialog(data)

        fun setAdjustDialog(data: AdjustDialogData?) = controllers.dialog.setAdjustDialog(data)

        fun setTrafficLimitationDialog(show: Boolean) = controllers.dialog.setTrafficLimitationDialog(show)

        fun setUsageAccessDialog(show: Boolean) = controllers.dialog.setUsageAccessDialog(show)

        fun setPhonePermissionPermanentlyDenied(denied: Boolean) =
            controllers.banner.setPhonePermissionPermanentlyDenied(denied)

        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) =
            controllers.banner.setNotificationPermissionPermanentlyDenied(denied)

        fun setNotificationPermissionRequested() = controllers.settings.setNotificationPermissionRequested()

        fun setPhonePermissionRequested() = controllers.settings.setPhonePermissionRequested()

        val showOnboardingDialog: StateFlow<Boolean> get() = controllers.dialog.showOnboardingDialog

        val appSettings: StateFlow<AppSettings> get() = controllers.settings.appSettings

        fun completeOnboarding() {
            controllers.dialog.setShowOnboardingDialog(false)
            controllers.settings.completeOnboarding()
        }

        val trackingBannerState: StateFlow<TrackingBannerState> get() = controllers.banner.trackingBannerState

        val uiState: StateFlow<SimCardListState> get() = controllers.simData.uiState

        val appTheme: StateFlow<AppTheme> get() = controllers.settings.appTheme

        init {
            initializeApp()
        }

        private fun initializeApp() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val settings = appSettingsRepository.getAppSettings().firstWithTimeout()
                    if (settings?.onboardingCompleted == false) {
                        controllers.dialog.setShowOnboardingDialog(true)
                    }
                    try {
                        val lastResetCheck = savedStateHandle.get<Long>(KEY_LAST_AUTO_RESET_CHECK) ?: 0L
                        val now = System.currentTimeMillis()
                        if (now - lastResetCheck > AUTO_RESET_CHECK_INTERVAL_MS) {
                            autoResetUseCase.performAutoReset(shouldNotify = false)
                            savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = now
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Auto-reset failed during initialization", e)
                    }
                    controllers.refresh.refreshSimCardsInternal()
                    if (lastRefreshAt == 0L) {
                        lastRefreshAt = SystemClock.elapsedRealtime()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to initialize app in ViewModel", e)
                }
            }
        }

        fun setTheme(theme: AppTheme) = controllers.settings.setTheme(theme)

        fun retryAppSearch() = controllers.simData.retryAppSearch()

        fun retryCalls() = controllers.simData.retryCalls()

        fun retryShortcuts() = controllers.simData.retryShortcuts()

        fun retryTraffic() {
            controllers.simData.retryTraffic { controllers.refresh.refreshSimCardsInternal() }
        }

        fun dismissWidgetError() = controllers.settings.dismissWidgetError()

        fun dismissInaccurateCalls() = controllers.settings.dismissInaccurateCalls()

        fun refreshSimCardsNow(onComplete: () -> Unit = {}) {
            viewModelScope.launch {
                controllers.refresh.refreshSimCardsInternal()
                lastRefreshAt = SystemClock.elapsedRealtime()
                onComplete()
            }
        }

        private suspend fun refreshAndNotify(force: Boolean = false) {
            val beforeHash = SnapshotUtils.calculateContentHash(controllers.simData.getSnapshot())
            controllers.refresh.refreshSimCardsInternal()
            lastRefreshAt = SystemClock.elapsedRealtime()

            val afterHash = SnapshotUtils.calculateContentHash(controllers.simData.getSnapshot())
            val hasChanged = beforeHash != afterHash

            /*
             * force=true используется для операций, требующих немедленного визуального отклика (сброс, ручная правка).
             * force=false уведомляет систему об изменениях, только если данные реально изменились,
             * чтобы избежать лишних перерисовок виджета в UpdateCoordinator.
             */
            if (force || hasChanged) {
                controllers.simData.notifyDataChanged(force = force)
            }
        }

        val appLanguage: StateFlow<String?> get() = controllers.settings.appLanguage

        fun setLanguage(language: String?) = controllers.settings.setLanguage(language)

        fun updateLimits(
            slot: Int,
            minutesLimit: Int,
            trafficLimitMb: Int,
            rolloverEnabled: Boolean = false,
        ) {
            controllers.simData.updateLimits(slot, minutesLimit, trafficLimitMb, rolloverEnabled) {
                refreshAndNotify()
            }
        }

        fun resetUsage(slot: Int) {
            controllers.simData.resetUsage(slot) { refreshAndNotify(force = true) }
        }

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) {
            controllers.simData.toggleAutoReset(slot, enabled) { refreshAndNotify() }
        }

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) {
            controllers.simData.updateAutoResetDay(slot, day) { refreshAndNotify() }
        }

        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            controllers.simData.adjustRemainingMinutes(slot, remainingMinutes) {
                refreshAndNotify(force = true)
            }
        }

        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            controllers.simData.adjustRemainingTraffic(slot, remainingGb) {
                refreshAndNotify(force = true)
            }
        }

        val highlightedSlot: StateFlow<Int?> = controllers.simData.highlightedSlot

        fun highlightSlot(slot: Int) = controllers.simData.highlightSlot(slot)

        private var lastRefreshAt: Long
            get() = savedStateHandle[KEY_LAST_REFRESH_AT] ?: 0L
            set(value) {
                savedStateHandle[KEY_LAST_REFRESH_AT] = value
            }

        fun onActivityResume(nowElapsed: Long) {
            val last = lastRefreshAt
            // Если last == 0L, это первый запуск в текущем сеансе.
            // Просто фиксируем время без немедленного обновления.
            if (last == 0L) {
                lastRefreshAt = nowElapsed
                return
            }
            val elapsed = nowElapsed - last
            // После перезагрузки elapsedRealtime сбрасывается; lastRefreshAt из SavedState может быть больше.
            // Отрицательный elapsed однозначно указывает на перезагрузку устройства.
            val isStale = elapsed < 0 || elapsed > Constants.UI.RESUME_REFRESH_THRESHOLD_MS

            if (isStale) {
                lastRefreshAt = nowElapsed
                viewModelScope.launch { refreshAndNotify(force = false) }
            }
        }

        companion object {
            private const val KEY_LAST_REFRESH_AT = "last_refresh_at"
            private const val KEY_LAST_AUTO_RESET_CHECK = "last_auto_reset_check"
            private const val AUTO_RESET_CHECK_INTERVAL_MS = 3_600_000L // 1 hour
        }
    }
