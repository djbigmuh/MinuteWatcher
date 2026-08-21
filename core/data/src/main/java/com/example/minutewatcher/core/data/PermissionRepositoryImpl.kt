package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [PermissionRepository] that observes permission states.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class PermissionRepositoryImpl
    @Inject
    constructor(
        private val permissionManager: PermissionManager,
        notifier: PermissionStateNotifier,
    ) : PermissionRepository {
        /**
         * Flow indicating if READ_PHONE_STATE permission is granted.
         *
         * Safe to call from any coroutine context.
         */
        override val phoneStatePermissionGranted: Flow<Boolean> =
            notifier.changes.map {
                permissionManager.hasPhoneStatePermission()
            }

        /**
         * Flow indicating if PACKAGE_USAGE_STATS permission is granted.
         *
         * Safe to call from any coroutine context.
         */
        override val usageStatsPermissionGranted: Flow<Boolean> =
            notifier.changes.map {
                permissionManager.hasUsageStatsPermission()
            }

        /**
         * Flow indicating if POST_NOTIFICATIONS permission is granted.
         *
         * Safe to call from any coroutine context.
         */
        override val notificationPermissionGranted: Flow<Boolean> =
            notifier.changes.map {
                permissionManager.hasNotificationPermission()
            }
    }
