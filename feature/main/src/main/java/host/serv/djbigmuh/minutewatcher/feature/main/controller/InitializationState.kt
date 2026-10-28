package host.serv.djbigmuh.minutewatcher.feature.main.controller

/**
 * Represents the state of the application initialization process.
 */
sealed interface InitializationState {
    /**
     * Initialization has not started yet.
     */
    data object NotStarted : InitializationState

    /**
     * Initialization is currently in progress.
     */
    data object InProgress : InitializationState

    /**
     * Initialization completed successfully.
     */
    data object Completed : InitializationState

    /**
     * Initialization failed after all retries.
     */
    data object Failed : InitializationState
}
