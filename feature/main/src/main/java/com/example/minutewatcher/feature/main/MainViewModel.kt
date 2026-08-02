package com.example.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.state.MainDialogStateHolder
import com.example.minutewatcher.feature.main.state.SimRefreshStateHolder
import com.example.minutewatcher.feature.main.state.TrackingBannerStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val deps: MainViewModelDependencies,
        savedStateHandle: SavedStateHandle,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val dialogStateHolder = MainDialogStateHolder(savedStateHandle)
        private val bannerStateHolder =
            TrackingBannerStateHolder(
                usageStateRepository = deps.usageStateRepository,
                permissionRepository = deps.permissionRepository,
                appSettingsRepository = deps.appSettingsRepository,
                scope = viewModelScope,
                stateFlowTimeout = Constants.Flow.STATE_FLOW_STOP_TIMEOUT_MS,
            )
        private val refreshStateHolder =
            SimRefreshStateHolder(
                usageUseCase = deps.usageUseCase,
                usageStateRepository = deps.usageStateRepository,
                ioDispatcher = ioDispatcher,
            )

        // Dialog States
        val showLimitDialog = dialogStateHolder.showLimitDialog
        val showDayDialog = dialogStateHolder.showDayDialog
        val showAdjustDialog = dialogStateHolder.showAdjustDialog
        val showTrafficLimitationDialog = dialogStateHolder.showTrafficLimitationDialog
        val showUsageAccessDialog = dialogStateHolder.showUsageAccessDialog

        fun setLimitDialog(data: LimitDialogData?) = dialogStateHolder.setLimitDialog(data)

        fun setDayDialog(data: DayDialogData?) = dialogStateHolder.setDayDialog(data)

        fun setAdjustDialog(data: AdjustDialogData?) = dialogStateHolder.setAdjustDialog(data)

        fun setTrafficLimitationDialog(show: Boolean) = dialogStateHolder.setTrafficLimitationDialog(show)

        fun setUsageAccessDialog(show: Boolean) = dialogStateHolder.setUsageAccessDialog(show)

        fun setPhonePermissionPermanentlyDenied(denied: Boolean) = bannerStateHolder.setPhonePermissionPermanentlyDenied(denied)

        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) =
            bannerStateHolder.setNotificationPermissionPermanentlyDenied(denied)

        fun setNotificationPermissionRequested() {
            viewModelScope.launch {
                deps.appSettingsRepository.updateAppSettings { it.copy(notificationPermissionRequested = true) }
            }
        }

        fun setPhonePermissionRequested() {
            viewModelScope.launch {
                deps.appSettingsRepository.updateAppSettings { it.copy(phonePermissionRequested = true) }
            }
        }

        val showOnboardingDialog: StateFlow<Boolean> = dialogStateHolder.showOnboardingDialog

        val appSettings: StateFlow<com.example.minutewatcher.core.model.AppSettings> =
            deps.appSettingsRepository
                .observeAppSettings()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue =
                        com.example.minutewatcher.core.model
                            .AppSettings(),
                )

        fun completeOnboarding() {
            dialogStateHolder.setShowOnboardingDialog(false)
            viewModelScope.launch {
                deps.appSettingsRepository.updateAppSettings { it.copy(onboardingCompleted = true) }
            }
        }

        val trackingBannerState: StateFlow<TrackingBannerState> = bannerStateHolder.trackingBannerState

        val uiState: StateFlow<SimCardListState> =
            deps.usageStateRepository
                .observeSimCardUiModels()
                .map { models ->
                    if (models.isEmpty()) SimCardListState.Empty else SimCardListState.Success(models)
                }.onStart { emit(SimCardListState.Loading) }
                .catch { e -> emit(SimCardListState.Error(e.message ?: "Unknown error")) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(Constants.Flow.STATE_FLOW_STOP_TIMEOUT_MS),
                    initialValue = SimCardListState.Loading,
                )

        val appTheme: StateFlow<AppTheme> =
            deps.settingsUseCase.appTheme
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(Constants.Flow.STATE_FLOW_STOP_TIMEOUT_MS),
                    initialValue = AppTheme.FOLLOW_SYSTEM,
                )

        init {
            initializeApp()
        }

        private fun initializeApp() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val settings = deps.appSettingsRepository.getAppSettings().firstWithTimeout()
                    if (settings?.onboardingCompleted == false) {
                        dialogStateHolder.setShowOnboardingDialog(true)
                    }
                    val result = deps.usageStateRepository.refresh()
                    deps.autoResetUseCase.performAutoReset(result.snapshot)
                    refreshSimCards()
                } catch (e: Exception) {
                    AppLogger.e("Failed to initialize app in ViewModel", e)
                }
            }
        }

        fun setTheme(theme: AppTheme) {
            viewModelScope.launch {
                deps.settingsUseCase.setTheme(theme)
            }
        }

        fun refreshSimCards() {
            viewModelScope.launch {
                refreshStateHolder.refreshSimCardsInternal()
            }
        }

        fun retryAppSearch() {
            deps.usageStateRepository.recoverTracking("appsearch")
        }

        fun retryCalls() {
            deps.usageStateRepository.recoverTracking("calls")
        }

        fun retryShortcuts() {
            deps.usageStateRepository.recoverTracking("shortcuts")
        }

        fun retryTraffic() {
            deps.usageStateRepository.recoverTracking("traffic")
            refreshSimCards()
        }

        fun refreshSimCardsNow(onComplete: () -> Unit = {}) {
            viewModelScope.launch {
                refreshStateHolder.refreshSimCardsInternal()
                onComplete()
            }
        }

        private suspend fun refreshAndNotify(force: Boolean = true) {
            refreshStateHolder.refreshSimCardsInternal()
            deps.dataChangeNotifier.notifyDataChanged(updateWidget = true, force = force)
        }

        fun notifyDataChanged(force: Boolean = false) {
            viewModelScope.launch {
                deps.dataChangeNotifier.notifyDataChanged(force = force)
            }
        }

        val appLanguage: StateFlow<String?> =
            deps.settingsUseCase.appLanguage
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(Constants.Flow.STATE_FLOW_STOP_TIMEOUT_MS),
                    initialValue = null,
                )

        fun setLanguage(language: String?) {
            viewModelScope.launch {
                deps.settingsUseCase.setLanguage(language)
            }
        }

        fun updateLimits(
            slot: Int,
            minutesLimit: Int,
            trafficLimitMb: Int,
            rolloverEnabled: Boolean = false,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val identity = deps.simIdentityRepository.getIdentity(slot)
                deps.simLimitsRepository.updateSimLimits(identity, minutesLimit, trafficLimitMb, rolloverEnabled)
                refreshAndNotify()
            }
        }

        fun resetUsage(slot: Int) {
            viewModelScope.launch(ioDispatcher) {
                deps.usageUseCase.resetCounters(slot)
                refreshAndNotify()
            }
        }

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val identity = deps.simIdentityRepository.getIdentity(slot)
                val currentState = uiState.value
                val currentDay =
                    if (currentState is SimCardListState.Success) {
                        currentState.simCards.find { it.slot == slot }?.autoResetDay ?: 1
                    } else {
                        deps.simLimitsRepository.getAutoResetDay(identity).firstWithTimeout() ?: 1
                    }
                deps.simLimitsRepository.setAutoReset(identity, currentDay, enabled)
                refreshAndNotify()
            }
        }

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val identity = deps.simIdentityRepository.getIdentity(slot)
                val currentState = uiState.value
                val enabled =
                    if (currentState is SimCardListState.Success) {
                        currentState.simCards.find { it.slot == slot }?.autoResetEnabled ?: false
                    } else {
                        deps.simLimitsRepository.getAutoReset(identity).firstWithTimeout() ?: false
                    }
                deps.simLimitsRepository.setAutoReset(identity, day, enabled)
                refreshAndNotify()
            }
        }

        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            viewModelScope.launch(ioDispatcher) {
                deps.usageUseCase.adjustMinutes(slot, remainingMinutes)
                refreshAndNotify()
            }
        }

        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            viewModelScope.launch(ioDispatcher) {
                deps.usageUseCase.adjustTraffic(slot, remainingGb)
                refreshAndNotify()
            }
        }

        private val _highlightedSlot = MutableStateFlow<Int?>(null)
        val highlightedSlot: StateFlow<Int?> = _highlightedSlot.asStateFlow()

        fun highlightSlot(slot: Int) {
            _highlightedSlot.value = slot
            viewModelScope.launch {
                delay(HIGHLIGHT_DELAY_SECONDS.seconds)
                if (_highlightedSlot.value == slot) {
                    _highlightedSlot.value = null
                }
            }
        }

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
                deps.permissionStateNotifier.notifyChanged()
                lastResumeNotifyTime = currentTimeMillis
            }
        }

        companion object {
            private const val HIGHLIGHT_DELAY_SECONDS = 3L
            private const val RESUME_REFRESH_THRESHOLD_MS = 20_000L
        }
    }
