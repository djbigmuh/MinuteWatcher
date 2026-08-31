package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * MainInitializationController handles app-wide initialization and retry logic.
 *
 * Safe to call from any coroutine context.
 */
class MainInitializationController
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val refreshController: MainSimRefreshController,
        private val dialogController: MainDialogController,
        private val autoResetScheduler: AutoResetScheduler,
        private val timeProvider: TimeProvider,
        @com.example.minutewatcher.core.common.di.IoDispatcher
        private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val _initializationState = MutableStateFlow<InitializationState>(InitializationState.NotStarted)
        val initializationState: StateFlow<InitializationState> = _initializationState.asStateFlow()

        private val isInitializationStarted = AtomicBoolean(false)

        fun initializeApp(
            scope: CoroutineScope,
            lastRefreshAt: Long,
            onUpdateLastRefreshAt: (Long) -> Unit,
        ) {
            if (!claimInitialization()) return

            autoResetScheduler.setup()
            _initializationState.value = InitializationState.InProgress

            scope.launch(ioDispatcher) {
                val isSuccess = runInitializationLoop(lastRefreshAt, onUpdateLastRefreshAt)
                finalizeInitialization(isSuccess)
            }
        }

    private fun claimInitialization(): Boolean {
        val currentState = _initializationState.value
        val alreadyStarted = isInitializationStarted.get()

        val canStart = !alreadyStarted || currentState is InitializationState.Failed

        return if (canStart && isInitializationStarted.compareAndSet(false, true)) {
            true
        } else {
            // Re-check after failed CAS in case state changed to Failed by another thread
            _initializationState.value is InitializationState.Failed &&
                isInitializationStarted.compareAndSet(false, true)
        }
    }

    private suspend fun runInitializationLoop(
        lastRefreshAt: Long,
        onUpdateLastRefreshAt: (Long) -> Unit,
    ): Boolean {
        var isSuccess = false
        var isTerminal = false
        var retryDelay = Constants.Flow.RETRY_DELAY_MS

        var attempt = 0
        while (attempt < MAX_INIT_RETRIES && !isSuccess && !isTerminal) {
            try {
                performInitializationTasks()
                val outcome = refreshController.refreshSimCardsInternal()
                isSuccess = handleRefreshOutcome(outcome, attempt, lastRefreshAt, onUpdateLastRefreshAt)

                if (!isSuccess && isTerminalFailure(outcome)) {
                    isTerminal = true
                }
            } catch (e: CancellationException) {
                isInitializationStarted.set(false)
                _initializationState.value = InitializationState.Failed
                throw e
            } catch (e: SecurityException) {
                AppLogger.e("initializeApp: SecurityException encountered, stopping initialization", e)
                isTerminal = true
            } catch (e: Exception) {
                AppLogger.e("initializeApp: attempt ${attempt + 1} failed with exception", e)
            }

            if (!isSuccess && !isTerminal && attempt < (MAX_INIT_RETRIES - 1)) {
                delay(retryDelay.milliseconds)
                retryDelay *= 2
            }
            attempt++
        }
        return isSuccess
    }

    private fun isTerminalFailure(outcome: MainSimRefreshController.RefreshOutcome): Boolean =
        (outcome is MainSimRefreshController.RefreshOutcome.Failed) &&
            (outcome.exception is SecurityException)

    private fun handleRefreshOutcome(
        outcome: MainSimRefreshController.RefreshOutcome,
        attempt: Int,
        lastRefreshAt: Long,
        onUpdateLastRefreshAt: (Long) -> Unit,
    ): Boolean {
        val isSuccess =
            (outcome is MainSimRefreshController.RefreshOutcome.Completed) &&
                (outcome.result is RefreshResult.Success)

        if (isSuccess) {
            if (lastRefreshAt == 0L) {
                onUpdateLastRefreshAt(timeProvider.now())
            }
            return true
        }

        logFailure(outcome, attempt)
        return false
    }

    private fun logFailure(
        outcome: MainSimRefreshController.RefreshOutcome,
        attempt: Int,
    ) {
        if (outcome is MainSimRefreshController.RefreshOutcome.Failed &&
            outcome.exception is SecurityException
        ) {
            AppLogger.e("initializeApp: SecurityException during refresh, stopping", outcome.exception)
        } else {
            if (outcome is MainSimRefreshController.RefreshOutcome.SkippedAlreadyInProgress) {
                AppLogger.d("initializeApp: refresh already in progress, will retry")
            }
            AppLogger.w("initializeApp: attempt ${attempt + 1} failed with outcome: $outcome")
            }
        }

        private suspend fun performInitializationTasks() {
            val settings = appSettingsRepository.getAppSettings().firstWithTimeout()
            if (settings?.onboardingCompleted == false) {
                dialogController.setShowOnboardingDialog(show = true)
            }
            autoResetScheduler.checkOnStart()
        }

        private fun finalizeInitialization(isSuccess: Boolean) {
            if (isSuccess) {
                _initializationState.value = InitializationState.Completed
            } else {
                isInitializationStarted.set(false) // Allow retry on failure
                _initializationState.value = InitializationState.Failed
            }
        }

        companion object {
            private const val MAX_INIT_RETRIES = 3
        }
    }
