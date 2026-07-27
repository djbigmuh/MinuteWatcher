package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Repository for observing permission states.
 */
interface PermissionRepository {
    /**
     * Flow emitting true if READ_PHONE_STATE permission is granted.
     */
    val phoneStatePermissionGranted: Flow<Boolean>

    /**
     * Flow emitting true if PACKAGE_USAGE_STATS permission is granted.
     */
    val usageStatsPermissionGranted: Flow<Boolean>

    /**
     * Flow emitting true if POST_NOTIFICATIONS permission is granted.
     */
    val notificationPermissionGranted: Flow<Boolean>
}
