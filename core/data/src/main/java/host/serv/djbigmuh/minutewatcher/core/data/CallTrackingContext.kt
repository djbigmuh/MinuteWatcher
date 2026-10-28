package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus

/**
 * Context for call tracking operations, providing access to status management
 * and permission checks.
 */
internal interface CallTrackingContext {
    /**
     * Updates the tracker status using prioritized transition rules.
     */
    fun updateStatus(
        status: TrackingStatus,
        force: Boolean = false,
    )

    /**
     * Checks if required permissions are granted.
     */
    fun checkPermissions(): Boolean

    /**
     * Refreshes the tracker status based on current system state.
     */
    suspend fun refreshStatus(force: Boolean = false)
}
