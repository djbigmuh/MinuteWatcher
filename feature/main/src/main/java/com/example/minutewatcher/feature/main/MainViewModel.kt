package com.example.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.feature.main.controller.MainDialogController
import com.example.minutewatcher.feature.main.controller.MainInitializationController
import com.example.minutewatcher.feature.main.controller.MainViewModelControllers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
        private val dialogController: MainDialogController,
        private val initController: MainInitializationController,
        private val timeProvider: TimeProvider,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        // Delegate UI States
        val showLimitDialog = dialogController.showLimitDialog
        val showDayDialog = dialogController.showDayDialog
        val showAdjustDialog = dialogController.showAdjustDialog
        val showTrafficLimitationDialog = dialogController.showTrafficLimitationDialog
        val showUsageAccessDialog = dialogController.showUsageAccessDialog
        val showOnboardingDialog: StateFlow<Boolean> get() = dialogController.showOnboardingDialog
        val isInitialSetupCompleted: Flow<Boolean> get() = controllers.settings.isInitialSetupCompleted
        val appSettings: StateFlow<AppSettings> get() = controllers.settings.appSettings
        val trackingBannerState: StateFlow<TrackingBannerState> get() = controllers.banner.trackingBannerState
        val uiState: StateFlow<SimCardListState> get() = controllers.simData.uiState
        val lastOperationError = controllers.simData.lastOperationError
        val appTheme: StateFlow<AppTheme> get() = controllers.settings.appTheme
        val appLanguage: StateFlow<String?> get() = controllers.settings.appLanguage
        val highlightedSlot: StateFlow<Int?> = controllers.simData.highlightedSlot

        init {
            initController.initializeApp(
                scope = viewModelScope,
                lastRefreshAt = lastRefreshAt,
            ) {
                lastRefreshAt = it
            }

            viewModelScope.launch {
                combine(
                    uiState,
                    appSettings,
                    showUsageAccessDialog,
                    showLimitDialog,
                    showOnboardingDialog,
                ) { state, settings, usageDialog, limitDialog, onboardingDialog ->
                    DataForPrompt(
                        state = state,
                        settings = settings,
                        usageAccess = usageDialog,
                        limitDialog = limitDialog,
                        onboardingDialogShowing = onboardingDialog,
                    )
                }.collect { data ->
                    val onboardingCompleted = data.settings.onboardingCompleted

                    // Logic for showing Onboarding Dialog
                    if (!onboardingCompleted && !data.onboardingDialogShowing && !data.usageAccess) {
                        AppLogger.d("MainViewModel: Triggering onboarding dialog")
                        dialogController.setShowOnboardingDialog(true)
                    }
                }
            }
        }

        private data class DataForPrompt(
            val state: SimCardListState,
            val settings: AppSettings,
            val usageAccess: Boolean,
            val limitDialog: LimitDialogData?,
            val onboardingDialogShowing: Boolean,
        )

        // Delegate Dialog/Banner Actions
        fun setLimitDialog(data: LimitDialogData?) = dialogController.setLimitDialog(data)

        fun setDayDialog(data: DayDialogData?) = dialogController.setDayDialog(data)

        fun setAdjustDialog(data: AdjustDialogData?) = dialogController.setAdjustDialog(data)

        fun setTrafficLimitationDialog(show: Boolean) = dialogController.setTrafficLimitationDialog(show)

        fun setUsageAccessDialog(show: Boolean) = dialogController.setUsageAccessDialog(show)

        fun setNotificationPermissionRequested() = controllers.settings.setNotificationPermissionRequested()

        fun setPhonePermissionRequested() = controllers.settings.setPhonePermissionRequested()

        fun completeOnboarding() {
            dialogController.setShowOnboardingDialog(show = false)
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

        fun retryTraffic() = controllers.simData.retryTraffic()

        fun dismissWidgetError() = controllers.settings.dismissWidgetError()

        fun dismissInaccurateCalls() = controllers.settings.dismissInaccurateCalls()

        fun dismissOperationError() = controllers.simData.dismissOperationError()

        fun retryBanners() {
            viewModelScope.launch {
                controllers.banner.recover()
            }
        }

        fun retryWidgetObservation() {
            viewModelScope.launch {
                try {
                    controllers.widgetObservationRecovery.get().recover()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainViewModel: retryWidgetObservation failed", e)
                }
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
            controllers.simData.updateLimits(slot, min, trafMb, roll)
        }

        fun resetUsage(slot: Int) = controllers.simData.resetUsage(slot)

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) = controllers.simData.toggleAutoReset(slot, enabled)

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) = controllers.simData.updateAutoResetDay(slot, day)

        fun adjustRemainingMinutes(
            slot: Int,
            min: Int,
        ) = controllers.simData.adjustRemainingMinutes(slot, min)

        fun adjustRemainingTraffic(
            slot: Int,
            gb: Float,
        ) = controllers.simData.adjustRemainingTraffic(slot, gb)

        fun highlightSlot(slot: Int) = controllers.simData.highlightSlot(slot)

        fun refreshSimCardsNow(onComplete: () -> Unit = {}) {
            controllers.refresh.refreshSimCardsNow(
                scope = viewModelScope,
                onNotifyChanged = { controllers.simData.notifyDataChanged(force = it) },
                onSuccess = { lastRefreshAt = timeProvider.elapsedRealtime() },
                onComplete = onComplete,
            )
        }

        private suspend fun refreshAndNotify(force: Boolean = false): Boolean =
            controllers.refresh.refreshAndNotify(
                onNotifyChanged = { controllers.simData.notifyDataChanged(force = it) },
                force = force,
            )

        private var lastRefreshAt: Long
            get() = savedStateHandle[KEY_LAST_REFRESH_TIME] ?: 0L
            set(value) {
                savedStateHandle[KEY_LAST_REFRESH_TIME] = value
            }

        fun onActivityResume() {
            controllers.resume.onActivityResume(
                scope = viewModelScope,
                initController = initController,
                onRefreshNeeded = {
                    refreshAndNotify(force = false)
                },
            ) {
                lastRefreshAt = it
            }
        }

        companion object {
            const val KEY_LAST_REFRESH_TIME = "last_refresh_time"
        }
    }
