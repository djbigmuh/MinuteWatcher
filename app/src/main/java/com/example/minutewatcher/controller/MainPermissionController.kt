package com.example.minutewatcher.controller

import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.data.WorkScheduler
import com.example.minutewatcher.feature.main.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Controller for handling permission checks and related initialization.
 * Prevents multiple concurrent checks using single-flight logic.
 */
class MainPermissionController
    @Inject
    constructor(
        private val permissionManager: PermissionManager,
        private val workScheduler: WorkScheduler,
        @IoDispatcher
        private val ioDispatcher: CoroutineDispatcher,
    ) {
        private var checkJob: Job? = null
        private var notificationJob: Job? = null

        /**
         * Checks permissions and schedules background work if needed.
         *
         * @param scope Coroutine scope to run the check.
         * @param viewModel ViewModel to interact with app settings and state.
         * @param lastPermissionState Bitmask of last known permission states.
         * @param onShowUsageAccessDialog Callback to show usage access dialog.
         * @param onRequestPhonePermission Callback to request phone state permission.
         * @param onCheckNotifications Callback to check for notification permission.
         */
        fun checkPermissionsAndInit(
            scope: CoroutineScope,
            viewModel: MainViewModel,
            lastPermissionState: Int,
            onShowUsageAccessDialog: (Boolean) -> Unit,
            onRequestPhonePermission: () -> Unit,
            onCheckNotifications: (Boolean) -> Unit,
        ) {
            checkJob?.cancel()
            checkJob =
                scope.launch {
                    val settings = viewModel.appSettings.filter { it.isLoaded }.first()
                    if (permissionManager.hasPhoneStatePermission()) {
                        launch(ioDispatcher) {
                            try {
                                val initialSetupCompleted = viewModel.isInitialSetupCompleted.first()
                                if (!initialSetupCompleted || lastPermissionState == -1) {
                                    workScheduler.scheduleInitialSetup(force = false)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("MainPermissionController: Failed to schedule work during init", e)
                            }
                        }

                        if (!permissionManager.hasUsageStatsPermission()) {
                            onShowUsageAccessDialog(true)
                        } else {
                            onCheckNotifications(true)
                        }
                    } else if (!settings.wasPhonePermissionRequestedBefore) {
                        onRequestPhonePermission()
                    }
                }
        }

        /**
         * Checks if notification permission should be requested.
         */
        fun checkNotifications(
            scope: CoroutineScope,
            viewModel: MainViewModel,
            autoRequest: Boolean,
            onRequest: () -> Unit,
        ) {
            notificationJob?.cancel()
            notificationJob =
                scope.launch {
                    if (!permissionManager.hasNotificationPermission()) {
                        val settings = viewModel.appSettings.filter { it.isLoaded }.first()
                        if (autoRequest && !settings.notificationPermissionRequested) {
                            onRequest()
                        }
                    }
                }
        }
    }
