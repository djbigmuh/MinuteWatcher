package com.example.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.feature.main.controller.MainViewModelControllers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * MainViewModel for the SIM card list screen.
 * Manages screen state, dialogs, banners, and SIM card data.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [IOException] and [CancellationException] from underlying repositories.
 * **AOSP DataStore:** Fully compliant with AOSP DataStore semantics for atomic updates and persistence.
 *
 * @param controllers Container for various controllers.
 * @param savedStateHandle Handle for state preservation across process death.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val controllers: MainViewModelControllers,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        // Delegate UI States
        val showLimitDialog = controllers.dialog.showLimitDialog
        val showDayDialog = controllers.dialog.showDayDialog
        val showAdjustDialog = controllers.dialog.showAdjustDialog
        val showTrafficLimitationDialog = controllers.dialog.showTrafficLimitationDialog
        val showUsageAccessDialog = controllers.dialog.showUsageAccessDialog
        val showOnboardingDialog: StateFlow<Boolean> get() = controllers.dialog.showOnboardingDialog
        val appSettings: StateFlow<AppSettings> get() = controllers.settings.appSettings
        val trackingBannerState: StateFlow<TrackingBannerState> get() = controllers.banner.trackingBannerState
        val uiState: StateFlow<SimCardListState> get() = controllers.simData.uiState
        val lastOperationError = controllers.simData.lastOperationError
        val appTheme: StateFlow<AppTheme> get() = controllers.settings.appTheme
        val appLanguage: StateFlow<String?> get() = controllers.settings.appLanguage
        val highlightedSlot: StateFlow<Int?> = controllers.simData.highlightedSlot

        init {
            controllers.init.initializeApp(
                scope = viewModelScope,
                lastRefreshAt = lastRefreshAt,
            ) {
                lastRefreshAt = it
            }
        }

        // Delegate Dialog/Banner Actions
        fun setLimitDialog(data: LimitDialogData?) = controllers.dialog.setLimitDialog(data)

        fun setDayDialog(data: DayDialogData?) = controllers.dialog.setDayDialog(data)

        fun setAdjustDialog(data: AdjustDialogData?) = controllers.dialog.setAdjustDialog(data)

        fun setTrafficLimitationDialog(show: Boolean) = controllers.dialog.setTrafficLimitationDialog(show)

        fun setUsageAccessDialog(show: Boolean) = controllers.dialog.setUsageAccessDialog(show)

    /**
     * Updates the permanently denied state for permissions.
     *
     * @param phone If provided, updates the phone permission denied state.
     * @param notification If provided, updates the notification permission denied state.
     */
    fun updatePermissionDeniedState(
        phone: Boolean? = null,
        notification: Boolean? = null,
    ) = controllers.banner.updatePermissionDeniedState(phone, notification)

        fun setNotificationPermissionRequested() = controllers.settings.setNotificationPermissionRequested()

        fun setPhonePermissionRequested() = controllers.settings.setPhonePermissionRequested()

        fun completeOnboarding() {
            controllers.dialog.setShowOnboardingDialog(show = false)
            controllers.settings.completeOnboarding()
        }

        // Delegate Recovery Actions
        fun retryAppSearch() {
            controllers.banner.incrementAppSearchRetry()
            controllers.simData.retryAppSearch()
        }

        fun dismissAppSearch() = controllers.banner.dismissAppSearch()

        fun retryCalls() = controllers.simData.retryCalls()

        fun retryShortcuts() = controllers.simData.retryShortcuts()

        fun retryTraffic() = controllers.simData.retryTraffic { refreshAndNotify(force = true) }

        fun dismissWidgetError() = controllers.settings.dismissWidgetError()

        fun dismissInaccurateCalls() = controllers.settings.dismissInaccurateCalls()

        fun dismissOperationError() = controllers.simData.dismissOperationError()

        fun retryBanners() {
            viewModelScope.launch {
                controllers.banner.recover()
            }
        }

        // Delegate Settings Actions
        fun setTheme(theme: AppTheme) = controllers.settings.setTheme(theme)

        fun setLanguage(language: String?) = controllers.settings.setLanguage(language)

        // Delegate SIM Data Actions
        fun updateLimits(
            slot: Int,
            min: Int,
            trafMb: Int,
            roll: Boolean = false,
        ) {
            controllers.simData.updateLimits(slot, min, trafMb, roll) { refreshAndNotify(force = true) }
        }

        fun resetUsage(slot: Int) = controllers.simData.resetUsage(slot) { refreshAndNotify(force = true) }

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) = controllers.simData.toggleAutoReset(slot, enabled) {
            refreshAndNotify(force = true)
        }

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) = controllers.simData.updateAutoResetDay(slot, day) {
            refreshAndNotify(force = true)
        }

        fun adjustRemainingMinutes(
            slot: Int,
            min: Int,
        ) = controllers.simData.adjustRemainingMinutes(slot, min) {
            refreshAndNotify(force = true)
        }

        fun adjustRemainingTraffic(
            slot: Int,
            gb: Float,
        ) = controllers.simData.adjustRemainingTraffic(slot, gb) {
            refreshAndNotify(force = true)
        }

        fun highlightSlot(slot: Int) = controllers.simData.highlightSlot(slot)

        fun refreshSimCardsNow(onComplete: () -> Unit = {}) {
            controllers.refresh.refreshSimCardsNow(
                scope = viewModelScope,
                onUpdateLastRefresh = { lastRefreshAt = it },
                onNotifyChanged = { controllers.simData.notifyDataChanged(force = it) },
                onComplete = onComplete,
            )
        }

        private suspend fun refreshAndNotify(force: Boolean = false) {
            controllers.refresh.refreshAndNotify(
                onUpdateLastRefresh = { lastRefreshAt = it },
                onNotifyChanged = { controllers.simData.notifyDataChanged(force = it) },
                force = force,
            )
        }

        private var lastRefreshAt: Long
            get() = savedStateHandle[KEY_LAST_REFRESH_ELAPSED] ?: 0L
            set(value) {
                savedStateHandle[KEY_LAST_REFRESH_ELAPSED] = value
            }

        fun onActivityResume() {
            controllers.resume.onActivityResume(
                scope = viewModelScope,
                initController = controllers.init,
                lastRefreshAt = lastRefreshAt,
                onRefreshNeeded = { refreshAndNotify(force = false) },
                onUpdateLastRefreshAt = { lastRefreshAt = it },
            )
        }

        companion object {
            internal const val KEY_LAST_REFRESH_ELAPSED = "last_refresh_elapsed"
        }
    }
