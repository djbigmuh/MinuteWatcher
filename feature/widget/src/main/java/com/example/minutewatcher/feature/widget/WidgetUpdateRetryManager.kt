package com.example.minutewatcher.feature.widget

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the retry loop for widget updates with exponential backoff.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class WidgetUpdateRetryManager @Inject constructor() {

    suspend fun updateWithRetry(
        updateBlock: suspend (Int) -> UpdateOutcome,
    ): Boolean {
        var delayMs = Constants.Widget.RETRY_BACKOFF_MS
        var result = false
        var attempt = 1
        var keepGoing = true

        while (attempt <= Constants.Widget.MAX_UPDATE_ATTEMPTS && keepGoing) {
            val outcome = updateBlock(attempt)

            when (outcome) {
                is UpdateOutcome.Success -> {
                    result = true
                    keepGoing = false
                }
                is UpdateOutcome.Stop -> {
                    keepGoing = false
                }
                is UpdateOutcome.Retry -> {
                    if (attempt == Constants.Widget.MAX_UPDATE_ATTEMPTS) {
                        AppLogger.e("Widget update exhausted all retry attempts.")
                        keepGoing = false
                    } else {
                        AppLogger.w("Widget update attempt $attempt failed, retrying in ${delayMs}ms")
                        delay(delayMs.milliseconds)
                        delayMs = (delayMs * 2).coerceAtMost(Constants.Widget.MAX_RETRY_DELAY_MS)
                        attempt++
                    }
                }
            }
        }

        if (!result) {
            AppLogger.e("Widget update failed after $attempt attempts")
        }
        return result
    }
}
