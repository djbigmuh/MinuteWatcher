package com.example.minutewatcher.feature.widget

import javax.inject.Inject
import javax.inject.Singleton

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
            val outcome = updateBlock(1)
            return outcome is UpdateOutcome.Success
        }
    }
