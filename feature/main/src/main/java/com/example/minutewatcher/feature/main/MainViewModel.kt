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
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.SnapshotUtils
import com.example.minutewatcher.feature.main.controller.MainViewModelControllers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        private val isInitialized = AtomicBoolean(false)
        private val lastSnapshotHash = AtomicInteger(-1)
        private var resumeRefreshJob: Job? = null

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
            controllers.dialog.setShowOnboardingDialog(show = false)
            controllers.settings.completeOnboarding()
        }

        val trackingBannerState: StateFlow<TrackingBannerState> get() = controllers.banner.trackingBannerState

        val uiState: StateFlow<SimCardListState> get() = controllers.simData.uiState

        val appTheme: StateFlow<AppTheme> get() = controllers.settings.appTheme

        init {
            initializeApp()
        }

        private fun initializeApp() {
            if (!isInitialized.compareAndSet(false, true)) return
            viewModelScope.launch(ioDispatcher) {
                try {
                    val settings = appSettingsRepository.getAppSettings().firstWithTimeout()
                    if (settings?.onboardingCompleted == false) {
                        controllers.dialog.setShowOnboardingDialog(show = true)
                    }

                    checkAutoResetOnStart()

                    val result = controllers.refresh.refreshSimCardsInternal()
                    result?.let {
                        lastSnapshotHash.set(SnapshotUtils.calculateContentHash(it.snapshot))
                        if (it is RefreshResult.Success && lastRefreshAt == 0L) {
                            lastRefreshAt = SystemClock.elapsedRealtime()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to initialize app in ViewModel", e)
                }
            }
        }

        private suspend fun checkAutoResetOnStart() {
            val lastResetCheck = savedStateHandle.get<Long>(KEY_LAST_AUTO_RESET_CHECK) ?: 0L
            val failCount = savedStateHandle.get<Int>(KEY_AUTO_RESET_FAIL_COUNT) ?: 0
            val now = System.currentTimeMillis()
            val effectiveFailCount =
                if ((now - lastResetCheck) > FAIL_COUNT_RESET_INTERVAL_MS) 0 else failCount

            try {
                if ((now - lastResetCheck) > AUTO_RESET_CHECK_INTERVAL_MS) {
                    if (effectiveFailCount <= MAX_AUTO_RESET_RETRIES) {
                        autoResetUseCase.performAutoReset(shouldNotify = false)
                        savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = now
                        savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = 0
                    } else {
                        AppLogger.e("Auto-reset skipped after $effectiveFailCount consecutive failures")
                        // Обновляем время, чтобы не спамить на каждом запуске
                        savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = now
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Auto-reset failed during initialization", e)
                savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = effectiveFailCount + 1
                savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = System.currentTimeMillis()
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
                try {
                    val result = controllers.refresh.refreshSimCardsInternal()
                    if (result is RefreshResult.Success) {
                        lastRefreshAt = SystemClock.elapsedRealtime()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("refreshSimCardsNow failed", e)
                } finally {
                    onComplete()
                }
            }
        }

        private suspend fun refreshAndNotify(force: Boolean = false) {
            val result = controllers.refresh.refreshSimCardsInternal()

            if (result == null) {
                AppLogger.w("refreshAndNotify: refresh returned null (in progress or error), widget not notified")
                return
            }

            if (result is RefreshResult.Success) {
                lastRefreshAt = SystemClock.elapsedRealtime()
            }

            val newHash = SnapshotUtils.calculateContentHash(result.snapshot)
            val hasChanged = newHash != lastSnapshotHash.get()
            lastSnapshotHash.set(newHash)

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
            if (!isInitialized.get()) return

            val last = lastRefreshAt
            val elapsed = nowElapsed - last
            // После перезагрузки elapsedRealtime сбрасывается; lastRefreshAt из SavedState может быть больше.
            // Отрицательный elapsed однозначно указывает на перезагрузку устройства.
            val isStale = (elapsed < 0) || (elapsed > Constants.UI.RESUME_REFRESH_THRESHOLD_MS)

            if (isStale) {
                resumeRefreshJob?.cancel()
                resumeRefreshJob = viewModelScope.launch { refreshAndNotify(force = false) }
            }
        }

        companion object {
            const val KEY_LAST_REFRESH_AT = "last_refresh_at"
            const val KEY_LAST_AUTO_RESET_CHECK = "last_auto_reset_check"
            const val KEY_AUTO_RESET_FAIL_COUNT = "auto_reset_fail_count"
            private const val AUTO_RESET_CHECK_INTERVAL_MS = 3_600_000L // 1 hour
            private const val MAX_AUTO_RESET_RETRIES = 3
            private const val FAIL_COUNT_RESET_INTERVAL_MS = 24 * 3_600_000L // 24 hours
        }
    }
