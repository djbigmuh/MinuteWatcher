package com.github.djbigmuh.minutewatcher.feature.widget

import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the retry loop for widget updates with exponential backoff.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class WidgetUpdateRetryManager
    @Inject
    constructor() {
        suspend fun updateWithRetry(updateBlock: suspend (Int) -> UpdateOutcome): Boolean {
            var currentDelay = Constants.Widget.RETRY_BACKOFF_MS
            var success = false
            var shouldStop = false

            var attempt = 1
            while ((attempt <= Constants.Widget.MAX_UPDATE_ATTEMPTS) && !success && !shouldStop) {
                @Suppress("TooGenericExceptionCaught")
                val outcome =
                    try {
                        updateBlock(attempt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Operation must complete without propagating exception for widget update retry attempt.
                        AppLogger.e("WidgetUpdateRetryManager: attempt $attempt failed with exception", e)
                        UpdateOutcome.Retry
                    }

                when (outcome) {
                    is UpdateOutcome.Success -> success = true
                    is UpdateOutcome.LimitExceeded, is UpdateOutcome.Stop -> shouldStop = true
                    is UpdateOutcome.Retry -> {
                        if (attempt < Constants.Widget.MAX_UPDATE_ATTEMPTS) {
                            delay(currentDelay.milliseconds)
                            currentDelay =
                                min(
                                    Constants.Widget.MAX_RETRY_DELAY_MS,
                                    currentDelay * 2,
                                )
                        }
                    }
                }
                attempt++
            }
            return success
        }
    }
