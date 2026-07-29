package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.android.util.PermissionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionRepositoryImpl
    @Inject
    constructor(
        private val permissionManager: PermissionManager,
        private val notifier: PermissionStateNotifier,
    ) : PermissionRepository {
        override val phoneStatePermissionGranted: Flow<Boolean> =
            notifier.changes.map {
                permissionManager.hasPhoneStatePermission()
            }

        override val usageStatsPermissionGranted: Flow<Boolean> =
            notifier.changes.map {
                permissionManager.hasUsageStatsPermission()
            }

        override val notificationPermissionGranted: Flow<Boolean> =
            notifier.changes.map {
                permissionManager.hasNotificationPermission()
            }
    }

