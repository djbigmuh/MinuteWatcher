package com.example.minutewatcher.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.feature.main.controller.MainBannerController
import com.example.minutewatcher.feature.main.controller.MainDialogController
import com.example.minutewatcher.feature.main.controller.MainSettingsController
import com.example.minutewatcher.feature.main.controller.MainSimDataController
import com.example.minutewatcher.feature.main.controller.MainSimRefreshController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        val dialogController: MainDialogController,
        val bannerController: MainBannerController,
        val refreshController: MainSimRefreshController,
        val settingsController: MainSettingsController,
        val simDataController: MainSimDataController,
        private val appSettingsRepository: AppSettingsRepository,
        private val usageStateRepository: UsageStateRepository,
        private val autoResetUseCase: AutoResetUseCase,
        private val permissionStateNotifier: PermissionStateNotifier,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        // Dialog States
        val showLimitDialog = dialogController.showLimitDialog
        val showDayDialog = dialogController.showDayDialog
        val showAdjustDialog = dialogController.showAdjustDialog
        val showTrafficLimitationDialog = dialogController.showTrafficLimitationDialog
        val showUsageAccessDialog = dialogController.showUsageAccessDialog

        fun setLimitDialog(data: LimitDialogData?) = dialogController.setLimitDialog(data)

        fun setDayDialog(data: DayDialogData?) = dialogController.setDayDialog(data)

        fun setAdjustDialog(data: AdjustDialogData?) = dialogController.setAdjustDialog(data)

        fun setTrafficLimitationDialog(show: Boolean) = dialogController.setTrafficLimitationDialog(show)

        fun setUsageAccessDialog(show: Boolean) = dialogController.setUsageAccessDialog(show)

        fun setPhonePermissionPermanentlyDenied(denied: Boolean) = bannerController.setPhonePermissionPermanentlyDenied(denied)

        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) =
            bannerController.setNotificationPermissionPermanentlyDenied(denied)

        fun setNotificationPermissionRequested() = settingsController.setNotificationPermissionRequested()

        fun setPhonePermissionRequested() = settingsController.setPhonePermissionRequested()

        val showOnboardingDialog: StateFlow<Boolean> = dialogController.showOnboardingDialog

        val appSettings: StateFlow<com.example.minutewatcher.core.model.AppSettings> = settingsController.appSettings

        fun completeOnboarding() {
            dialogController.setShowOnboardingDialog(false)
            settingsController.completeOnboarding()
        }

        val trackingBannerState: StateFlow<TrackingBannerState> = bannerController.trackingBannerState

        val uiState: StateFlow<SimCardListState> = simDataController.uiState

        val appTheme: StateFlow<AppTheme> = settingsController.appTheme

        init {
            bannerController.initialize(viewModelScope)
            settingsController.initialize(viewModelScope)
            simDataController.initialize(viewModelScope)
            initializeApp()
        }

        private fun initializeApp() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val settings = appSettingsRepository.getAppSettings().firstWithTimeout()
                    if (settings?.onboardingCompleted == false) {
                        dialogController.setShowOnboardingDialog(true)
                    }
                    val result = usageStateRepository.refresh()
                    autoResetUseCase.performAutoReset(result.snapshot)
                    refreshSimCards()
                } catch (e: Exception) {
                    AppLogger.e("Failed to initialize app in ViewModel", e)
                }
            }
        }

        fun setTheme(theme: AppTheme) = settingsController.setTheme(theme)

        fun refreshSimCards() {
            viewModelScope.launch {
                refreshController.refreshSimCardsInternal()
            }
        }

        fun retryAppSearch() = simDataController.retryAppSearch()

        fun retryCalls() = simDataController.retryCalls()

        fun retryShortcuts() = simDataController.retryShortcuts()

        fun retryTraffic() {
            simDataController.retryTraffic { refreshController.refreshSimCardsInternal() }
        }

        fun dismissWidgetError() = settingsController.dismissWidgetError()

        fun dismissInaccurateCalls() = settingsController.dismissInaccurateCalls()

        fun refreshSimCardsNow(onComplete: () -> Unit = {}) {
            viewModelScope.launch {
                refreshController.refreshSimCardsInternal()
                onComplete()
            }
        }

        private suspend fun refreshAndNotify(force: Boolean = true) {
            refreshController.refreshSimCardsInternal()
            simDataController.notifyDataChanged(force = force)
        }

        fun notifyDataChanged(force: Boolean = false) = simDataController.notifyDataChanged(force = force)

        val appLanguage: StateFlow<String?> = settingsController.appLanguage

        fun setLanguage(language: String?) = settingsController.setLanguage(language)

        fun updateLimits(
            slot: Int,
            minutesLimit: Int,
            trafficLimitMb: Int,
            rolloverEnabled: Boolean = false,
        ) {
            simDataController.updateLimits(slot, minutesLimit, trafficLimitMb, rolloverEnabled) {
                refreshAndNotify()
            }
        }

        fun resetUsage(slot: Int) {
            simDataController.resetUsage(slot) { refreshAndNotify() }
        }

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) {
            simDataController.toggleAutoReset(slot, enabled) { refreshAndNotify() }
        }

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) {
            simDataController.updateAutoResetDay(slot, day) { refreshAndNotify() }
        }

        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            simDataController.adjustRemainingMinutes(slot, remainingMinutes) { refreshAndNotify() }
        }

        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            simDataController.adjustRemainingTraffic(slot, remainingGb) { refreshAndNotify() }
        }

        val highlightedSlot: StateFlow<Int?> = simDataController.highlightedSlot

        fun highlightSlot(slot: Int) = simDataController.highlightSlot(slot)

        private var lastRefreshAt = 0L
        private var lastResumeNotifyTime = 0L

        fun onActivityResume(
            nowElapsed: Long,
            currentTimeMillis: Long,
        ) {
            if (nowElapsed - lastRefreshAt > RESUME_REFRESH_THRESHOLD_MS) {
                lastRefreshAt = nowElapsed
                refreshSimCards()
                notifyDataChanged()
            }

            if (currentTimeMillis - lastResumeNotifyTime > Constants.MILLIS_PER_SECOND) {
                permissionStateNotifier.notifyChanged()
                lastResumeNotifyTime = currentTimeMillis
            }
        }

        companion object {
            private const val RESUME_REFRESH_THRESHOLD_MS = 20_000L
        }
    }
