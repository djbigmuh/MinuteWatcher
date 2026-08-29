package com.example.minutewatcher.feature.widget

import com.example.minutewatcher.core.model.Constants
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

            for (attempt in 1..Constants.Widget.MAX_UPDATE_ATTEMPTS) {
                val outcome = updateBlock(attempt)
                when (outcome) {
                    is UpdateOutcome.Success -> return true
                    is UpdateOutcome.LimitExceeded -> return false
                    is UpdateOutcome.Stop -> return false
                    is UpdateOutcome.Retry -> {
                        if (attempt == Constants.Widget.MAX_UPDATE_ATTEMPTS) return false

                        delay(currentDelay.milliseconds)
                        currentDelay =
                            min(
                                Constants.Widget.MAX_RETRY_DELAY_MS,
                                currentDelay * 2,
                            )
                    }
                }
            }
            return false
        }
    }
