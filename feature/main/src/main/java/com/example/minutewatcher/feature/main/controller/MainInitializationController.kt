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
        private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val _initializationState = MutableStateFlow(false)
        val initializationState: StateFlow<Boolean> = _initializationState.asStateFlow()

        private val isInitializationStarted = AtomicBoolean(false)

        fun initializeApp(
            scope: CoroutineScope,
            lastRefreshAt: Long,
            onUpdateLastRefreshAt: (Long) -> Unit,
        ) {
            if (!isInitializationStarted.compareAndSet(false, true)) return

            _initializationState.value = false

            scope.launch(ioDispatcher) {
                try {
                    val settings = appSettingsRepository.getAppSettings().firstWithTimeout()
                    if (settings?.onboardingCompleted == false) {
                        dialogController.setShowOnboardingDialog(show = true)
                    }

                    autoResetScheduler.checkOnStart()

                    var result: RefreshResult? = null
                    var retryDelay = Constants.Flow.RETRY_DELAY_MS
                    for (attempt in 0..MAX_INIT_RETRIES) {
                        result = refreshController.refreshSimCardsInternal()
                        if (result is RefreshResult.Success) break

                        if (attempt < MAX_INIT_RETRIES) {
                            val reason = when (result) {
                                is RefreshResult.Timeout -> "timeout"
                                null -> "busy/error"
                            }
                            AppLogger.d(
                                "initializeApp: refresh failed ($reason), " +
                                    "retrying in ${retryDelay}ms (attempt ${attempt + 1}/$MAX_INIT_RETRIES)",
                            )
                            delay(retryDelay.milliseconds)
                            retryDelay *= 2
                        }
                    }

                    if (result is RefreshResult.Success) {
                        if (lastRefreshAt == 0L) {
                            onUpdateLastRefreshAt(timeProvider.elapsedRealtime())
                        }
                    } else {
                        val reason = if (result is RefreshResult.Timeout) "Timeout" else "Error/Busy"
                        AppLogger.e("initializeApp: refresh failed after all retries ($reason)")
                    }
                } catch (e: CancellationException) {
                    isInitializationStarted.set(false)
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to initialize app in Controller", e)
                    isInitializationStarted.set(false)
                } finally {
                    _initializationState.value = true
                }
            }
        }

        companion object {
            private const val MAX_INIT_RETRIES = 3
        }
    }
