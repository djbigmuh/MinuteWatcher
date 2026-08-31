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
            // Guard: Allow if not started OR if failed (for retry from resume)
            if (isInitializationStarted.get() && (_initializationState.value !is InitializationState.Failed)) return
            isInitializationStarted.set(true)

            autoResetScheduler.setup()
            _initializationState.value = InitializationState.InProgress

            scope.launch(ioDispatcher) {
                var isSuccess = false
                var retryDelay = Constants.Flow.RETRY_DELAY_MS

                for (attempt in 0 until MAX_INIT_RETRIES) {
                    try {
                        performInitializationTasks()

                        val outcome = refreshController.refreshSimCardsInternal()
                        val refreshSuccessful =
                            (
                                (outcome is MainSimRefreshController.RefreshOutcome.Completed) &&
                                    (outcome.result is RefreshResult.Success)
                            )

                        if (refreshSuccessful) {
                            isSuccess = true
                            if (lastRefreshAt == 0L) {
                                onUpdateLastRefreshAt(timeProvider.now())
                            }
                            break
                        } else {
                            if ((outcome is MainSimRefreshController.RefreshOutcome.Failed) && (outcome.exception is SecurityException)) {
                                AppLogger.e(
                                    "initializeApp: SecurityException during refresh, stopping",
                                    outcome.exception
                                )
                                isSuccess = false
                                break
                            }
                            val alreadyInProgress =
                                outcome is MainSimRefreshController.RefreshOutcome.SkippedAlreadyInProgress
                            if (alreadyInProgress) {
                                AppLogger.d("initializeApp: refresh already in progress, will retry")
                            }
                            AppLogger.w("initializeApp: attempt ${attempt + 1} failed with outcome: $outcome")
                        }
                    } catch (e: CancellationException) {
                        isInitializationStarted.set(false)
                        _initializationState.value = InitializationState.Failed
                        throw e
                    } catch (e: SecurityException) {
                        AppLogger.e("initializeApp: SecurityException encountered, stopping initialization", e)
                        isSuccess = false
                        break
                    } catch (e: Exception) {
                        AppLogger.e("initializeApp: attempt ${attempt + 1} failed with exception", e)
                    }

                    if (attempt < (MAX_INIT_RETRIES - 1)) {
                        AppLogger.d("initializeApp: retrying in ${retryDelay}ms...")
                        delay(retryDelay.milliseconds)
                        retryDelay *= 2
                    }
                }
                finalizeInitialization(isSuccess)
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
                _initializationState.value = InitializationState.Failed
            }
        }

        companion object {
            private const val MAX_INIT_RETRIES = 3
        }
    }
