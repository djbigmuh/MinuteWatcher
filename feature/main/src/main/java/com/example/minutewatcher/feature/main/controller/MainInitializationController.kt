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
            if (!isInitializationStarted.compareAndSet(false, true)) return

            _initializationState.value = InitializationState.InProgress

            scope.launch(ioDispatcher) {
                var isSuccess = false
                try {
                    performInitializationTasks()

                    val result = refreshSimCardsWithRetry()
                    if (result is RefreshResult.Success) {
                        isSuccess = true
                        if (lastRefreshAt == 0L) {
                            onUpdateLastRefreshAt(timeProvider.elapsedRealtime())
                        }
                    } else {
                        logRefreshFailure(result)
                    }
                } catch (e: CancellationException) {
                    handleInitCancellation(e)
                } catch (e: Exception) {
                    handleInitException(e)
                } finally {
                    finalizeInitialization(isSuccess)
                }
            }
        }

        private suspend fun performInitializationTasks() {
            val settings = appSettingsRepository.getAppSettings().firstWithTimeout()
            if (settings?.onboardingCompleted == false) {
                dialogController.setShowOnboardingDialog(show = true)
            }
            autoResetScheduler.checkOnStart()
        }

        private suspend fun refreshSimCardsWithRetry(): RefreshResult? {
            var result: RefreshResult? = null
            var retryDelay = Constants.Flow.RETRY_DELAY_MS
            for (attempt in 0..MAX_INIT_RETRIES) {
                result = refreshController.refreshSimCardsInternal()
                if (result is RefreshResult.Success) break

                if (attempt < MAX_INIT_RETRIES) {
                    logRetryAttempt(result, retryDelay, attempt)
                    delay(retryDelay.milliseconds)
                    retryDelay *= 2
                }
            }
            return result
        }

        private fun logRetryAttempt(
            result: RefreshResult?,
            delay: Long,
            attempt: Int,
        ) {
            val reason =
                when (result) {
                    is RefreshResult.Timeout -> "timeout"
                    else -> "busy/error"
                }
            AppLogger.d(
                "initializeApp: refresh failed ($reason), " +
                    "retrying in ${delay}ms (attempt ${attempt + 1}/$MAX_INIT_RETRIES)",
            )
        }

        private fun logRefreshFailure(result: RefreshResult?) {
            val reason = if (result is RefreshResult.Timeout) "Timeout" else "Error/Busy"
            AppLogger.e("initializeApp: refresh failed after all retries ($reason)")
        }

        private fun handleInitCancellation(e: CancellationException) {
            isInitializationStarted.set(false)
            _initializationState.value = InitializationState.Failed
            throw e
        }

        private fun handleInitException(e: Exception) {
            AppLogger.e("Failed to initialize app in Controller", e)
            isInitializationStarted.set(false)
        }

        private fun finalizeInitialization(isSuccess: Boolean) {
            if (isSuccess) {
                _initializationState.value = InitializationState.Completed
            } else {
                isInitializationStarted.set(false)
                _initializationState.value = InitializationState.Failed
            }
        }

        companion object {
            private const val MAX_INIT_RETRIES = 3
        }
    }
