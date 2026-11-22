package com.github.djbigmuh.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.domain.AppInfoProvider
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.AppTheme
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.feature.main.controller.MainDialogController
import com.github.djbigmuh.minutewatcher.feature.main.controller.MainInitializationController
import com.github.djbigmuh.minutewatcher.feature.main.controller.MainViewModelControllers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * MainViewModel for the SIM card list screen.
 * Manages screen state, dialogs, banners, and SIM card data.
 * Acts as a facade for UI actions, delegating to controllers.
 * The number of functions is determined by the variety of user interactions available on the screen.
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
    internal constructor(
        private val controllers: MainViewModelControllers,
        private val dialogController: MainDialogController,
        private val initController: MainInitializationController,
        private val timeProvider: TimeProvider,
        private val appInfoProvider: AppInfoProvider,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        // Delegate UI States
        val showLimitDialog = dialogController.showLimitDialog
        val showDayDialog = dialogController.showDayDialog
        val showAdjustDialog = dialogController.showAdjustDialog
        val showTrafficLimitationDialog = dialogController.showTrafficLimitationDialog
        val showUsageAccessDialog = dialogController.showUsageAccessDialog
        val showAboutDialog = dialogController.showAboutDialog
        val versionName: String get() = appInfoProvider.versionName
        val versionCode: Int get() = appInfoProvider.versionCode
        val showOnboardingDialog: StateFlow<Boolean> get() = dialogController.showOnboardingDialog
        val isInitialSetupCompleted: Flow<Boolean> get() = controllers.settings.isInitialSetupCompleted
        val appSettings: StateFlow<AppSettings> get() = controllers.settings.appSettings
        val trackingBannerState: StateFlow<TrackingBannerState> get() = controllers.banner.trackingBannerState

    val isSwipeAvailable: StateFlow<Boolean> =
        combine(
            showOnboardingDialog,
            showUsageAccessDialog,
        ) { onboarding, usageAccess ->
            !onboarding && !usageAccess
        }.distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                initialValue = true,
            )

    val isSwipeHintDismissed: StateFlow<Boolean> =
        controllers.settings.appSettings
            .map { settings: AppSettings -> settings.swipeHintDismissed }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                initialValue = controllers.settings.appSettings.value.swipeHintDismissed,
            )

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

        /**
         * Observes the UI state of SIM cards, bound to the viewModelScope.
         */
        val uiState: StateFlow<SimCardListState> =
            controllers.simData.observeUiState(viewModelScope)

        val lastOperationError = controllers.simData.lastOperationError

        /**
         * Hot flows for theme and language, bound to the viewModelScope.
         */
        val appTheme: StateFlow<AppTheme> =
            controllers.settings.appTheme
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                    initialValue = AppTheme.FOLLOW_SYSTEM,
                )

        val appLanguage: StateFlow<String?> =
            controllers.settings.appLanguage
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                    initialValue = null,
                )

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
                    appSettings,
                    showUsageAccessDialog,
                    showOnboardingDialog,
                ) { settings, usageDialog, onboardingDialog ->
                    OnboardingPromptInput(
                        isSettingsLoaded = settings.isLoaded,
                        onboardingCompleted = settings.onboardingCompleted,
                        usageAccessDialogShown = usageDialog,
                        onboardingDialogShowing = onboardingDialog,
                    )
                }.distinctUntilChanged().collect { data ->
                    val shouldShowOnboarding = data.isSettingsLoaded && !data.onboardingCompleted
                    val isUiBlocked = data.onboardingDialogShowing || data.usageAccessDialogShown

                    // Logic for showing Onboarding Dialog
                    if (shouldShowOnboarding && !isUiBlocked && dialogController.claimOnboardingPrompt()) {
                        AppLogger.d("MainViewModel: Triggering onboarding dialog")
                        dialogController.setShowOnboardingDialog(true)
                    }
                }
            }
        }

        private data class OnboardingPromptInput(
            val isSettingsLoaded: Boolean,
            val onboardingCompleted: Boolean,
            val usageAccessDialogShown: Boolean,
            val onboardingDialogShowing: Boolean,
        )

        // Delegate Dialog/Banner Actions
        fun setLimitDialog(data: LimitDialogData?) = dialogController.setLimitDialog(data)

        fun setDayDialog(data: DayDialogData?) = dialogController.setDayDialog(data)

        fun setAdjustDialog(data: AdjustDialogData?) = dialogController.setAdjustDialog(data)

        fun setTrafficLimitationDialog(show: Boolean) = dialogController.setTrafficLimitationDialog(show)

        fun setUsageAccessDialog(show: Boolean) = dialogController.setUsageAccessDialog(show)

        fun setShowAboutDialog(show: Boolean) = dialogController.setShowAboutDialog(show)

        fun setNotificationPermissionRequested() = controllers.settings.setNotificationPermissionRequested()

        fun setPhonePermissionRequested() = controllers.settings.setPhonePermissionRequested()

        fun setPhonePermissionPermanentlyDenied(denied: Boolean) =
            controllers.settings.setPhonePermissionPermanentlyDenied(denied)

        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) =
            controllers.settings.setNotificationPermissionPermanentlyDenied(denied)

        fun markUsageAccessDialogShown() = controllers.settings.markUsageAccessDialogShown()

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

        fun retrySimDiscovery() = controllers.simData.retrySimDiscovery()

        fun dismissWidgetError() = controllers.settings.dismissWidgetError()

        fun dismissInaccurateCalls() = controllers.settings.dismissInaccurateCalls()

        fun dismissOperationError() = controllers.simData.dismissOperationError()

        fun retryWidgetObservation() {
            viewModelScope.launch {
                @Suppress("TooGenericExceptionCaught")
                try {
                    controllers.widgetObservationRecovery.get().recover()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for widget observation recovery.
                    AppLogger.e("MainViewModel: retryWidgetObservation failed", e)
                }
            }
        }

        // Delegate Settings Actions
        fun setTheme(theme: AppTheme) = controllers.settings.setTheme(theme)

        fun setLanguage(language: String?) = controllers.settings.setLanguage(language)

    fun dismissSwipeHint() = controllers.settings.dismissSwipeHint()

        // Delegate SIM Data Actions
        fun retrySimList() = controllers.simData.retrySimList()

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
            _isRefreshing.value = true
            controllers.refresh.refreshSimCardsNow(
                scope = viewModelScope,
                onNotifyChanged = { controllers.simData.notifyDataChanged(force = it) },
                onSuccess = { lastRefreshAt = timeProvider.elapsedRealtime() },
                onComplete = {
                    _isRefreshing.value = false
                    onComplete()
                },
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
                    refreshAndNotify()
                },
            ) {
                lastRefreshAt = it
            }
        }

        companion object {
            const val KEY_LAST_REFRESH_TIME = "last_refresh_time"
        }
    }
