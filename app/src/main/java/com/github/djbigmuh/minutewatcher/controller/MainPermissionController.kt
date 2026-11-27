package com.github.djbigmuh.minutewatcher.controller

import com.github.djbigmuh.minutewatcher.core.common.di.IoDispatcher
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.common.util.firstWithTimeout
import com.github.djbigmuh.minutewatcher.core.data.WorkScheduler
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

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
        private val checkJob = AtomicReference<Job?>(null)
        private val notificationJob = AtomicReference<Job?>(null)
        private val checkJobLock = Any()
        private val notificationJobLock = Any()

        /**
         * Checks permissions and schedules background work if needed.
         *
         * @param scope Coroutine scope to run the check.
         * @param isInitialSetupCompleted Flow of initial setup completion status.
         * @param appSettingsLoaded Flow of loaded app settings.
         * @param lastPermissionState Bitmask of last known permission states.
         * @param onShowUsageAccessDialog Callback to show usage access dialog.
         * @param onRequestPhonePermission Callback to request phone state permission.
         * @param onCheckNotifications Callback to check for notification permission.
         */
        fun checkPermissionsAndInit(
            scope: CoroutineScope,
            isInitialSetupCompleted: Flow<Boolean>,
            appSettingsLoaded: Flow<AppSettings>,
            lastPermissionState: Int,
            onShowUsageAccessDialog: (Boolean) -> Unit,
            onRequestPhonePermission: () -> Unit,
            onCheckNotifications: (Boolean) -> Unit,
        ) {
            synchronized(checkJobLock) {
                val previous = checkJob.get()
                val newJob =
                    scope.launch(start = CoroutineStart.LAZY) {
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            if (permissionManager.hasPhoneStatePermission()) {
                                handlePhoneStateGranted(
                                    this,
                                    isInitialSetupCompleted,
                                    appSettingsLoaded,
                                    lastPermissionState,
                                    onShowUsageAccessDialog,
                                    onCheckNotifications,
                                )
                            } else {
                                handlePhoneStateDenied(appSettingsLoaded, onRequestPhonePermission)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Operation must complete without propagating exception for permission flow stability.
                            AppLogger.e("MainPermissionController: Failed during checkPermissionsAndInit", e)
                        } finally {
                            checkJob.compareAndSet(currentCoroutineContext().job, null)
                        }
                    }
                checkJob.set(newJob)
                previous?.cancel()
                newJob.start()
            }
        }

        /**
         * Launches background scheduling for initial setup if setup is not completed or state is reset.
         */
        private fun CoroutineScope.launchInitialSetupScheduling(
            isInitialSetupCompleted: Flow<Boolean>,
            lastPermissionState: Int,
        ) {
            launch(ioDispatcher) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    val setupCompleted =
                        isInitialSetupCompleted.firstWithTimeout(
                            Constants.Flow.LONG_TIMEOUT_MS,
                        )
                    if (setupCompleted != true ||
                        lastPermissionState == -1
                    ) {
                        workScheduler.scheduleInitialSetup(force = false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Operation must complete without propagating exception
                    // for permission flow stability.
                    AppLogger.e("MainPermissionController: Failed to schedule work during init", e)
                }
            }
        }

        /**
         * Handles actions when phone state permission is granted.
         */
        private suspend fun handlePhoneStateGranted(
            scope: CoroutineScope,
            isInitialSetupCompleted: Flow<Boolean>,
            appSettingsLoaded: Flow<AppSettings>,
            lastPermissionState: Int,
            onShowUsageAccessDialog: (Boolean) -> Unit,
            onCheckNotifications: (Boolean) -> Unit,
        ) {
            scope.launchInitialSetupScheduling(isInitialSetupCompleted, lastPermissionState)

            if (!permissionManager.hasUsageStatsPermission()) {
                val settings = readLoadedSettings(appSettingsLoaded, "init")
                if (settings != null && !settings.usageAccessDialogShown) {
                    onShowUsageAccessDialog(true)
                }
            } else {
                // Notification permission is requested only after the user enables notifications in settings.
                onCheckNotifications(false)
            }
        }

        /**
         * Handles actions when phone state permission is denied.
         */
        private suspend fun handlePhoneStateDenied(
            appSettingsLoaded: Flow<AppSettings>,
            onRequestPhonePermission: () -> Unit,
        ) {
            val settings = readLoadedSettings(appSettingsLoaded, "init")
            if (settings != null && !settings.wasPhonePermissionRequestedBefore) {
                onRequestPhonePermission()
            }
        }

        /**
         * Checks if notification permission should be requested.
         */
        fun checkNotifications(
            scope: CoroutineScope,
            appSettingsLoaded: Flow<AppSettings>,
            autoRequest: Boolean,
            onRequest: () -> Unit,
        ) {
            synchronized(notificationJobLock) {
                val previous = notificationJob.get()
                val newJob =
                    scope.launch(start = CoroutineStart.LAZY) {
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            if (!permissionManager.hasNotificationPermission()) {
                                val settings = readLoadedSettings(appSettingsLoaded, "checkNotifications")
                                if (autoRequest && settings != null && !settings.notificationPermissionRequested) {
                                    onRequest()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Operation must complete without propagating exception for permission flow stability.
                            AppLogger.e("MainPermissionController: Failed during checkNotifications", e)
                        } finally {
                            notificationJob.compareAndSet(currentCoroutineContext().job, null)
                        }
                    }
                notificationJob.set(newJob)
                previous?.cancel()
                newJob.start()
            }
        }

        private suspend fun readLoadedSettings(
            appSettingsLoaded: Flow<AppSettings>,
            actionName: String,
        ): AppSettings? =
            withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                appSettingsLoaded.firstOrNull { it.isLoaded }
            } ?: run {
                AppLogger.w(
                    "MainPermissionController: AppSettings read timed " +
                        "out during $actionName",
                )
                null
            }
    }
