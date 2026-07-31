package com.example.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.RefreshState
import com.example.minutewatcher.core.model.TrackingStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simLimitsRepository: SimLimitsRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val usageStateRepository: UsageStateRepository,
        private val autoResetUseCase: AutoResetUseCase,
        private val usageUseCase: UsageUseCase,
        private val settingsUseCase: SettingsUseCase,
        permissionRepository: PermissionRepository,
        private val dataChangeNotifier: DataChangeNotifier,
        private val savedStateHandle: SavedStateHandle,
        @com.example.minutewatcher.core.common.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        // Dialog States
        val showLimitDialog = savedStateHandle.getStateFlow<LimitDialogData?>("limit_dialog", null)
        val showDayDialog = savedStateHandle.getStateFlow<DayDialogData?>("day_dialog", null)
        val showAdjustDialog = savedStateHandle.getStateFlow<AdjustDialogData?>("adjust_dialog", null)
        val showTrafficLimitationDialog = savedStateHandle.getStateFlow("traffic_limitation_dialog", false)
        val showUsageAccessDialog = savedStateHandle.getStateFlow("usage_access_dialog", false)

        fun setLimitDialog(data: LimitDialogData?) { savedStateHandle["limit_dialog"] = data }
        fun setDayDialog(data: DayDialogData?) { savedStateHandle["day_dialog"] = data }
        fun setAdjustDialog(data: AdjustDialogData?) { savedStateHandle["adjust_dialog"] = data }
        fun setTrafficLimitationDialog(show: Boolean) { savedStateHandle["traffic_limitation_dialog"] = show }
        fun setUsageAccessDialog(show: Boolean) { savedStateHandle["usage_access_dialog"] = show }

        private val isPhonePermissionPermanentlyDeniedFlow = MutableStateFlow(false)

        fun setPhonePermissionPermanentlyDenied(denied: Boolean) {
            isPhonePermissionPermanentlyDeniedFlow.value = denied
        }

        private val isNotificationPermissionPermanentlyDeniedFlow = MutableStateFlow(false)

        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) {
            isNotificationPermissionPermanentlyDeniedFlow.value = denied
        }

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

        private val _showOnboardingDialog = MutableStateFlow(false)
        val showOnboardingDialog: StateFlow<Boolean> = _showOnboardingDialog.asStateFlow()

        val appSettings: StateFlow<com.example.minutewatcher.core.model.AppSettings> =
            appSettingsRepository
                .observeAppSettings()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = com.example.minutewatcher.core.model.AppSettings(),
                )

        fun completeOnboarding() {
            _showOnboardingDialog.value = false
            viewModelScope.launch {
                appSettingsRepository.updateAppSettings { it.copy(onboardingCompleted = true) }
            }
        }

        val trackingStatus: StateFlow<Map<String, TrackingStatus>> =
            usageStateRepository
                .observeTrackingStatus()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STATE_FLOW_TIMEOUT),
                    initialValue = emptyMap(),
                )


        val trackingBannerState: StateFlow<TrackingBannerState> =
            combine(
                combine(
                    trackingStatus,
                    permissionRepository.phoneStatePermissionGranted,
                    permissionRepository.usageStatsPermissionGranted,
                ) { statusMap, phoneGranted, usageGranted ->
                    Triple(statusMap, phoneGranted, usageGranted)
                },
                combine(
                    permissionRepository.notificationPermissionGranted,
                    isPhonePermissionPermanentlyDeniedFlow,
                    isNotificationPermissionPermanentlyDeniedFlow,
                    appSettingsRepository.observeAppSettings(),
                ) { notificationsGranted, phonePermanentlyDenied, notificationPermanentlyDenied, settings ->
                    BannerInputsPart2(notificationsGranted, phonePermanentlyDenied, notificationPermanentlyDenied, settings)
                },
            ) { part1, part2 ->
                val (statusMap, phoneGranted, usageGranted) = part1
                val (notificationsGranted, phonePermanentlyDenied, notificationPermanentlyDenied, settings) = part2

                TrackingBannerMapper.mapToBannerState(
                    statusMap = statusMap,
                    phoneGranted = phoneGranted,
                    usageGranted = usageGranted,
                    notificationsGranted = notificationsGranted,
                    phonePermanentlyDenied = phonePermanentlyDenied,
                    notificationPermanentlyDenied = notificationPermanentlyDenied,
                    settings = settings
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STATE_FLOW_TIMEOUT),
                initialValue = TrackingBannerState(),
            )

        private data class BannerInputsPart2(
            val notificationsGranted: Boolean,
            val phonePermanentlyDenied: Boolean,
            val notificationPermanentlyDenied: Boolean,
            val settings: com.example.minutewatcher.core.model.AppSettings,
        )

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

        private val _refreshStatus = MutableStateFlow<RefreshState>(RefreshState.Idle)
        val refreshStatus: StateFlow<RefreshState> = _refreshStatus.asStateFlow()

        fun consumeRefreshStatus() {
            _refreshStatus.value = RefreshState.Idle
        }

        private val simCheckMutex = Mutex()

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
                val settings = appSettingsRepository.getAppSettings().first()
                if (!settings.onboardingCompleted) {
                    _showOnboardingDialog.value = true
                }
                val snapshot = usageStateRepository.observeSnapshot().value
                autoResetUseCase.performAutoReset(snapshot)
                refreshSimCards()
            }
        }

        fun setTheme(theme: AppTheme) {
            viewModelScope.launch {
                settingsUseCase.setTheme(theme)
            }
        }

        fun refreshSimCards() {
            viewModelScope.launch {
                refreshSimCardsInternal()
            }
        }

        fun retryAppSearch() {
            usageStateRepository.recoverTracking("appsearch")
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
                refreshSimCardsInternal()
                onComplete()
            }
        }

        private suspend fun refreshSimCardsInternal() =
            withContext(ioDispatcher) {
                val acquired =
                    withTimeoutOrNull(2.seconds) {
                        simCheckMutex.withLock {
                            _refreshStatus.value = RefreshState.Loading
                            try {
                                usageUseCase.refreshOperatorInfo()
                                usageStateRepository.refresh()
                                _refreshStatus.value = RefreshState.Success
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                com.example.minutewatcher.core.common.util.AppLogger
                                    .e("Error refreshing SIM cards", e)
                                _refreshStatus.value = RefreshState.Error(e.message)
                            }
                            true
                        }
                    } ?: false

                if (!acquired) {
                    _refreshStatus.value = RefreshState.Error("Refresh timed out")
                }
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
                refreshSimCards()
                notifyDataChanged(force = true)
            }
        }

        fun resetUsage(slot: Int) {
            viewModelScope.launch(ioDispatcher) {
                val identity = simIdentityRepository.getIdentity(slot)
                simUsageRepository.resetSimUsage(identity)
                refreshSimCards()
                notifyDataChanged(force = true)
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
                        simLimitsRepository.getAutoResetDay(identity).first()
                    }
                simLimitsRepository.setAutoReset(identity, currentDay, enabled)
                refreshSimCards()
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
                        simLimitsRepository.getAutoReset(identity).first()
                    }
                simLimitsRepository.setAutoReset(identity, day, enabled)
                refreshSimCards()
            }
        }

        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            viewModelScope.launch(ioDispatcher) {
                usageUseCase.adjustMinutes(slot, remainingMinutes)
                refreshSimCards()
                notifyDataChanged(force = true)
            }
        }

        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            viewModelScope.launch(ioDispatcher) {
                usageUseCase.adjustTraffic(slot, remainingGb)
                refreshSimCards()
                notifyDataChanged(force = true)
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
