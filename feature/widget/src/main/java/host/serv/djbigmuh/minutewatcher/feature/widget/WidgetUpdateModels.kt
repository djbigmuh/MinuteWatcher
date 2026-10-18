package host.serv.djbigmuh.minutewatcher.feature.widget

/**
 * Result of a low-level widget update attempt.
 */
sealed interface UpdateResult {
    data class Success(
        val wasFull: Boolean,
    ) : UpdateResult

    data class LimitExceeded(
        val wasFull: Boolean,
    ) : UpdateResult

    data class GenericFailure(
        val isRetryable: Boolean,
    ) : UpdateResult
}

/**
 * High-level outcome of a widget update attempt, guiding the retry loop.
 */
sealed interface UpdateOutcome {
    data object Success : UpdateOutcome

    data object Retry : UpdateOutcome

    data object Stop : UpdateOutcome

    data class LimitExceeded(
        val wasFull: Boolean,
    ) : UpdateOutcome
}
