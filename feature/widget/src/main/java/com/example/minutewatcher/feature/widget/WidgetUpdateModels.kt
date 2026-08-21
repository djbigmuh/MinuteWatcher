package com.example.minutewatcher.feature.widget

/**
 * Result of a low-level widget update attempt.
 */
sealed class UpdateResult {
    data class Success(val wasFull: Boolean) : UpdateResult()
    object LimitExceeded : UpdateResult()
    data class GenericFailure(val isRetryable: Boolean) : UpdateResult()
}

/**
 * High-level outcome of a widget update attempt, guiding the retry loop.
 */
sealed class UpdateOutcome {
    data class Success(val shouldStop: Boolean = true) : UpdateOutcome()
    object Retry : UpdateOutcome()
    object Stop : UpdateOutcome()
}
