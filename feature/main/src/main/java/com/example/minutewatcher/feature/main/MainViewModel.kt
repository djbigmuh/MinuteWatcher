package com.example.minutewatcher.feature.main

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.data.ResetManager
import com.example.minutewatcher.core.data.UpdateCoordinator
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.TrackingStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val simRepository: SimRepository,
        private val settingsRepository: SettingsRepository,
        private val usageStateRepository: UsageStateRepository,
        private val resetManager: ResetManager,
        private val usageUseCase: UsageUseCase,
        private val settingsUseCase: SettingsUseCase,
        private val updateCoordinator: UpdateCoordinator,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        // Dialog States
        val showLimitDialog = savedStateHandle.getStateFlow<LimitDialogData?>("limit_dialog", null)
        val showDayDialog = savedStateHandle.getStateFlow<DayDialogData?>("day_dialog", null)
        val showAdjustDialog = savedStateHandle.getStateFlow<AdjustDialogData?>("adjust_dialog", null)
        val showTrafficLimitationDialog = savedStateHandle.getStateFlow<Boolean>("traffic_limitation_dialog", false)
        val showUsageAccessDialog = savedStateHandle.getStateFlow<Boolean>("usage_access_dialog", false)

        fun openLimitDialog(data: LimitDialogData) {
            savedStateHandle["limit_dialog"] = data
        }

        fun closeLimitDialog() {
            savedStateHandle["limit_dialog"] = null
        }

        fun openDayDialog(data: DayDialogData) {
            savedStateHandle["day_dialog"] = data
        }

        fun closeDayDialog() {
            savedStateHandle["day_dialog"] = null
        }

        fun openAdjustDialog(data: AdjustDialogData) {
            savedStateHandle["adjust_dialog"] = data
        }

        fun closeAdjustDialog() {
            savedStateHandle["adjust_dialog"] = null
        }

        fun openTrafficLimitationDialog() {
            savedStateHandle["traffic_limitation_dialog"] = true
        }

        fun closeTrafficLimitationDialog() {
            savedStateHandle["traffic_limitation_dialog"] = false
        }

        private val _isPhonePermissionPermanentlyDenied = MutableStateFlow(false)

        fun setPhonePermissionPermanentlyDenied(denied: Boolean) {
            _isPhonePermissionPermanentlyDenied.value = denied
        }

        fun openUsageAccessDialog() {
            savedStateHandle["usage_access_dialog"] = true
        }

        fun closeUsageAccessDialog() {
            savedStateHandle["usage_access_dialog"] = false
        }

        val trackingStatus: StateFlow<Map<String, TrackingStatus>> =
            usageStateRepository
                .observeTrackingStatus()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = emptyMap(),
                )

        val trackingBannerState: StateFlow<TrackingBannerState> =
            combine(
                trackingStatus,
                _isPhonePermissionPermanentlyDenied
            ) { statusMap, permanentlyDenied ->
                val calls = statusMap["calls"]
                val traffic = statusMap["traffic"]
                val appsearch = statusMap["appsearch"]

                TrackingBannerState(
                    showPhonePermissionBanner = calls is TrackingStatus.Error &&
                        calls.type == TrackingStatus.ErrorType.PERMISSION_MISSING,
                    isPhonePermissionPermanentlyDenied = permanentlyDenied,
                    showUsageAccessBanner = traffic is TrackingStatus.Error &&
                        traffic.type == TrackingStatus.ErrorType.PERMISSION_MISSING,
                    showTrafficErrorBanner = traffic is TrackingStatus.Error &&
                        traffic.type != TrackingStatus.ErrorType.PERMISSION_MISSING,
                    showAppSearchErrorBanner = appsearch is TrackingStatus.Error,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = TrackingBannerState(),
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
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = SimCardListState.Loading,
                )

        private val simCheckMutex = Mutex()

        val appTheme: StateFlow<AppTheme> =
            settingsUseCase.appTheme
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = AppTheme.FOLLOW_SYSTEM,
                )

        init {
            initializeApp()
        }

        private fun initializeApp() {
            viewModelScope.launch(Dispatchers.IO) {
                val snapshot = usageStateRepository.observeSnapshot().value
                resetManager.performAutoReset(simRepository, settingsRepository, snapshot)
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

        suspend fun refreshSimCardsNow() {
            refreshSimCardsInternal()
        }

        private suspend fun refreshSimCardsInternal() =
            withContext(Dispatchers.IO) {
                simCheckMutex.withLock {
                    try {
                        usageUseCase.refreshOperatorInfo()
                        usageStateRepository.refresh()
                    } catch (e: Exception) {
                        com.example.minutewatcher.core.model.util.AppLogger
                            .e("Error refreshing SIM cards", e)
                    }
                }
            }

        fun notifyDataChanged(force: Boolean = false) {
            viewModelScope.launch {
                updateCoordinator.notifyDataChanged(force = force)
            }
        }

        val appLanguage: Flow<String?> = settingsUseCase.appLanguage

        fun setLanguage(language: String?) {
            viewModelScope.launch {
                settingsUseCase.setLanguage(language)
            }
        }

        fun updateLimits(
            slot: Int,
            minutesLimit: Int,
            trafficLimitMb: Int,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                val identity = simRepository.getIdentity(slot)
                simRepository.updateSimLimits(identity, minutesLimit, trafficLimitMb)
                refreshSimCards()
                notifyDataChanged(force = true)
            }
        }

        fun resetUsage(slot: Int) {
            viewModelScope.launch(Dispatchers.IO) {
                val identity = simRepository.getIdentity(slot)
                simRepository.resetSimUsage(identity)
                refreshSimCards()
                notifyDataChanged(force = true)
            }
        }

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                val identity = simRepository.getIdentity(slot)
                val currentState = uiState.value
                val currentDay =
                    if (currentState is SimCardListState.Success) {
                        currentState.simCards.find { it.slot == slot }?.autoResetDay ?: 1
                    } else {
                        simRepository.getAutoResetDay(identity).first()
                    }
                simRepository.setAutoReset(identity, currentDay, enabled)
                refreshSimCards()
            }
        }

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                val identity = simRepository.getIdentity(slot)
                val currentState = uiState.value
                val enabled =
                    if (currentState is SimCardListState.Success) {
                        currentState.simCards.find { it.slot == slot }?.autoResetEnabled ?: false
                    } else {
                        simRepository.getAutoReset(identity).first()
                    }
                simRepository.setAutoReset(identity, day, enabled)
                refreshSimCards()
            }
        }

        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                usageUseCase.adjustMinutes(slot, remainingMinutes)
                refreshSimCards()
                notifyDataChanged(force = true)
            }
        }

        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
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
                delay(3.seconds)
                if (_highlightedSlot.value == slot) {
                    _highlightedSlot.value = null
                }
            }
        }
    }
