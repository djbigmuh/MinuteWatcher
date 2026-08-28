package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
        @ApplicationScope scope: CoroutineScope,
    ) : PermissionRepository {
        /**
         * Flow indicating if READ_PHONE_STATE permission is granted.
         *
         * Safe to call from any coroutine context.
         */
        override val phoneStatePermissionGranted: StateFlow<Boolean> =
            notifier.changes
                .map {
                    permissionManager.hasPhoneStatePermission()
                }.distinctUntilChanged()
                .stateIn(
                    scope = scope,
                    // Eagerly: поток критичен для корректности UI, остановка недопустима.
                    started = SharingStarted.Eagerly,
                    initialValue = permissionManager.hasPhoneStatePermission(),
                )

        /**
         * Flow indicating if PACKAGE_USAGE_STATS permission is granted.
         *
         * Safe to call from any coroutine context.
         */
        override val usageStatsPermissionGranted: StateFlow<Boolean> =
            notifier.changes
                .map {
                    permissionManager.hasUsageStatsPermission()
                }.distinctUntilChanged()
                .stateIn(
                    scope = scope,
                    // Eagerly: поток критичен для корректности UI, остановка недопустима.
                    started = SharingStarted.Eagerly,
                    initialValue = permissionManager.hasUsageStatsPermission(),
                )

        /**
         * Flow indicating if POST_NOTIFICATIONS permission is granted.
         *
         * Safe to call from any coroutine context.
         */
        override val notificationPermissionGranted: StateFlow<Boolean> =
            notifier.changes
                .map {
                    permissionManager.hasNotificationPermission()
                }.distinctUntilChanged()
                .stateIn(
                    scope = scope,
                    // Eagerly: поток критичен для корректности UI, остановка недопустима.
                    started = SharingStarted.Eagerly,
                    initialValue = permissionManager.hasNotificationPermission(),
                )
    }
