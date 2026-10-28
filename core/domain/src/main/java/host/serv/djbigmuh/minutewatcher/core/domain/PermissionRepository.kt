package host.serv.djbigmuh.minutewatcher.core.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for observing permission states.
 *
 * All flows are safe to collect from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices if applicable.
 */
interface PermissionRepository {
    /**
     * Flow emitting true if READ_PHONE_STATE permission is granted.
     */
    val phoneStatePermissionGranted: StateFlow<Boolean>

    /**
     * Flow emitting true if PACKAGE_USAGE_STATS permission is granted.
     */
    val usageStatsPermissionGranted: StateFlow<Boolean>

    /**
     * Flow emitting true if POST_NOTIFICATIONS permission is granted.
     */
    val notificationPermissionGranted: StateFlow<Boolean>
}
