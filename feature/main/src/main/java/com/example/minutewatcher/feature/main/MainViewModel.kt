package com.example.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.RefreshState
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
        private val simIdentityRepository: SimIdentityRepository,
        private val simLimitsRepository: SimLimitsRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val usageStateRepository: UsageStateRepository,
        private val autoResetUseCase: AutoResetUseCase,
        private val usageUseCase: UsageUseCase,
        private val settingsUseCase: SettingsUseCase,
        permissionRepository: PermissionRepository,
        private val dataChangeNotifier: DataChangeNotifier,
        savedStateHandle: SavedStateHandle,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val dialogStateHolder = MainDialogStateHolder(savedStateHandle)
        private val bannerStateHolder =
            TrackingBannerStateHolder(
                usageStateRepository = usageStateRepository,
                permissionRepository = permissionRepository,
                appSettingsRepository = appSettingsRepository,
                scope = viewModelScope,
                stateFlowTimeout = STATE_FLOW_TIMEOUT,
            )
        private val refreshStateHolder =
            SimRefreshStateHolder(
                usageUseCase = usageUseCase,
                usageStateRepository = usageStateRepository,
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
                appSettingsRepository.updateAppSettings { it.copy(notificationPermissionRequested = true) }
            }
        }

        fun setPhonePermissionRequested() {
            viewModelScope.launch {
                appSettingsRepository.updateAppSettings { it.copy(phonePermissionRequested = true) }
            }
        }

        val showOnboardingDialog: StateFlow<Boolean> = dialogStateHolder.showOnboardingDialog

        val appSettings: StateFlow<com.example.minutewatcher.core.model.AppSettings> =
            appSettingsRepository
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
                appSettingsRepository.updateAppSettings { it.copy(onboardingCompleted = true) }
            }
        }

        val trackingStatus: StateFlow<Map<String, TrackingStatus>> = bannerStateHolder.trackingStatus
        val trackingBannerState: StateFlow<TrackingBannerState> = bannerStateHolder.trackingBannerState

        val uiState: StateFlow<SimCardListState> =
            usageStateRepository
                .observeSimCardUiModels()
                .map { models ->
                    if (models.isEmpty()) SimCardListState.Empty else SimCardListState.Success(models)
                }.onStart { emit(SimCardListState.Loading) }
                .catch { e -> emit(SimCardListState.Error(e.message ?: "Unknown error")) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STATE_FLOW_TIMEOUT),
                    initialValue = SimCardListState.Loading,
                )

        val refreshStatus: StateFlow<RefreshState> = refreshStateHolder.refreshStatus

        fun consumeRefreshStatus() = refreshStateHolder.consumeRefreshStatus()

        val appTheme: StateFlow<AppTheme> =
            settingsUseCase.appTheme
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STATE_FLOW_TIMEOUT),
                    initialValue = AppTheme.FOLLOW_SYSTEM,
                )

        init {
            initializeApp()
        }

        private fun initializeApp() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val settings = appSettingsRepository.getAppSettings().firstWithTimeout()
                    if (settings?.onboardingCompleted == false) {
                        dialogStateHolder.setShowOnboardingDialog(true)
                    }
                    val snapshot = usageStateRepository.observeSnapshot().value
                    autoResetUseCase.performAutoReset(snapshot)
                    refreshSimCards()
                } catch (e: Exception) {
                    AppLogger.e("Failed to initialize app in ViewModel", e)
                }
            }
        }

        fun setTheme(theme: AppTheme) {
            viewModelScope.launch {
                settingsUseCase.setTheme(theme)
            }
        }

        fun refreshSimCards() {
            viewModelScope.launch {
                refreshStateHolder.refreshSimCardsInternal()
            }
        }

        fun retryAppSearch() {
            usageStateRepository.recoverTracking("appsearch")
        }

        fun retryCalls() {
            usageStateRepository.recoverTracking("calls")
        }

        fun retryShortcuts() {
            usageStateRepository.recoverTracking("shortcuts")
        }

        fun retryTraffic() {
            usageStateRepository.recoverTracking("traffic")
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
            dataChangeNotifier.notifyDataChanged(updateWidget = true, force = force)
        }

        fun notifyDataChanged(force: Boolean = false) {
            viewModelScope.launch {
                dataChangeNotifier.notifyDataChanged(force = force)
            }
        }

        val appLanguage: StateFlow<String?> =
            settingsUseCase.appLanguage
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STATE_FLOW_TIMEOUT),
                    initialValue = null,
                )

        fun setLanguage(language: String?) {
            viewModelScope.launch {
                settingsUseCase.setLanguage(language)
            }
        }

        fun updateLimits(
            slot: Int,
            minutesLimit: Int,
            trafficLimitMb: Int,
            rolloverEnabled: Boolean = false,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val identity = simIdentityRepository.getIdentity(slot)
                simLimitsRepository.updateSimLimits(identity, minutesLimit, trafficLimitMb, rolloverEnabled)
                refreshAndNotify()
            }
        }

        fun resetUsage(slot: Int) {
            viewModelScope.launch(ioDispatcher) {
                usageUseCase.resetCounters(slot)
                refreshAndNotify()
            }
        }

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val identity = simIdentityRepository.getIdentity(slot)
                val currentState = uiState.value
                val currentDay =
                    if (currentState is SimCardListState.Success) {
                        currentState.simCards.find { it.slot == slot }?.autoResetDay ?: 1
                    } else {
                        simLimitsRepository.getAutoResetDay(identity).firstWithTimeout() ?: 1
                    }
                simLimitsRepository.setAutoReset(identity, currentDay, enabled)
                refreshAndNotify()
            }
        }

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val identity = simIdentityRepository.getIdentity(slot)
                val currentState = uiState.value
                val enabled =
                    if (currentState is SimCardListState.Success) {
                        currentState.simCards.find { it.slot == slot }?.autoResetEnabled ?: false
                    } else {
                        simLimitsRepository.getAutoReset(identity).firstWithTimeout() ?: false
                    }
                simLimitsRepository.setAutoReset(identity, day, enabled)
                refreshAndNotify()
            }
        }

        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            viewModelScope.launch(ioDispatcher) {
                usageUseCase.adjustMinutes(slot, remainingMinutes)
                refreshAndNotify()
            }
        }

        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            viewModelScope.launch(ioDispatcher) {
                usageUseCase.adjustTraffic(slot, remainingGb)
                refreshAndNotify()
            }
        }

        private val _highlightedSlot = MutableStateFlow<Int?>(null)
        val highlightedSlot: StateFlow<Int?> = _highlightedSlot.asStateFlow()

        fun highlightSlot(slot: Int) {
            _highlightedSlot.value = slot
            viewModelScope.launch {
                delay(HIGHLIGHT_DELAY.seconds)
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
            permissionStateNotifier: com.example.minutewatcher.core.domain.PermissionStateNotifier,
        ) {
            if (nowElapsed - lastRefreshAt > 20_000L) {
                lastRefreshAt = nowElapsed
                refreshSimCards()
                notifyDataChanged()
            }

            if (currentTimeMillis - lastResumeNotifyTime > 1000) {
                permissionStateNotifier.notifyChanged()
                lastResumeNotifyTime = currentTimeMillis
            }
        }

        companion object {
            private const val STATE_FLOW_TIMEOUT = 5_000L
            private const val HIGHLIGHT_DELAY = 3L
        }
    }
