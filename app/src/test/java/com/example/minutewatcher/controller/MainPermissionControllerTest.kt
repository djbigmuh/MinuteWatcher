package com.example.minutewatcher.controller

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.WorkScheduler
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MainPermissionControllerTest {
    private val permissionManager: PermissionManager = mock()
    private val workScheduler: WorkScheduler = mock()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var controller: MainPermissionController

    private val appSettingsFlow = MutableStateFlow(AppSettings(isLoaded = false))
    private val isInitialSetupCompletedFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        initTestLogger()
        controller = MainPermissionController(permissionManager, workScheduler, testDispatcher)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
    }

    @Test
    fun `checkPermissionsAndInit waits for isLoaded before requesting phone permission`() =
        runTest(testDispatcher) {
            var phoneRequested = false

            controller.checkPermissionsAndInit(
                scope = this,
                isInitialSetupCompleted = isInitialSetupCompletedFlow,
                appSettingsLoaded = appSettingsFlow,
                lastPermissionState = -1,
                onShowUsageAccessDialog = {},
                onRequestPhonePermission = { phoneRequested = true },
                onCheckNotifications = {},
            )

            runCurrent()
            assertFalse("Should not request until loaded", phoneRequested)

            // Load settings
            appSettingsFlow.value = AppSettings(isLoaded = true, wasPhonePermissionRequestedBefore = false)
            runCurrent()

            assertTrue("Should request phone permission when loaded and not requested before", phoneRequested)
        }

    @Test
    fun `checkPermissionsAndInit schedules setup if phone permission already granted`() =
        runTest(testDispatcher) {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)

            var notificationsChecked = false
            appSettingsFlow.value = AppSettings(isLoaded = true)

            controller.checkPermissionsAndInit(
                scope = this,
                isInitialSetupCompleted = isInitialSetupCompletedFlow,
                appSettingsLoaded = appSettingsFlow,
                lastPermissionState = -1,
                onShowUsageAccessDialog = {},
                onRequestPhonePermission = {},
                onCheckNotifications = { notificationsChecked = true },
            )

            advanceUntilIdle()
            verify(workScheduler).scheduleInitialSetup(any(), any())
            assertTrue("Should check notifications if phone and usage granted", notificationsChecked)
        }

    @Test
    fun `checkPermissionsAndInit handles appSettings timeout`() =
        runTest(testDispatcher) {
            var phoneRequested = false
            controller.checkPermissionsAndInit(
                scope = this,
                isInitialSetupCompleted = isInitialSetupCompletedFlow,
                appSettingsLoaded = MutableStateFlow(AppSettings(isLoaded = false)),
                lastPermissionState = 0,
                onShowUsageAccessDialog = {},
                onRequestPhonePermission = { phoneRequested = true },
                onCheckNotifications = {},
            )

            // Wait for LONG_TIMEOUT_MS
            advanceTimeBy(Constants.Flow.LONG_TIMEOUT_MS.milliseconds + 100.milliseconds)
            runCurrent()

            assertFalse("Phone permission should not be requested on timeout", phoneRequested)
        }

    @Test
    fun `checkPermissionsAndInit handles initialSetupCompleted timeout`() =
        runTest(testDispatcher) {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
            appSettingsFlow.value = AppSettings(isLoaded = true)

            controller.checkPermissionsAndInit(
                scope = this,
                isInitialSetupCompleted = flow { awaitCancellation() },
                appSettingsLoaded = appSettingsFlow,
                lastPermissionState = 0,
                onShowUsageAccessDialog = {},
                onRequestPhonePermission = {},
                onCheckNotifications = {},
            )

            advanceTimeBy(Constants.Flow.LONG_TIMEOUT_MS.milliseconds + 100.milliseconds)
            advanceUntilIdle()

            // Should schedule setup because check timed out (null result)
            verify(workScheduler).scheduleInitialSetup(eq(false), eq(false))
        }

    @Test
    fun `checkPermissionsAndInit proceeds immediately without waiting for appSettings when phone permission granted`() =
        runTest(testDispatcher) {
            withTimeout(5000.milliseconds) {
                whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
                whenever(permissionManager.hasUsageStatsPermission()).thenReturn(false)

                var showUsageAccessDialogCalled = false

                controller.checkPermissionsAndInit(
                    scope = this,
                    isInitialSetupCompleted = isInitialSetupCompletedFlow,
                    appSettingsLoaded = MutableStateFlow(AppSettings(isLoaded = false)),
                    lastPermissionState = -1,
                    onShowUsageAccessDialog = { showUsageAccessDialogCalled = true },
                    onRequestPhonePermission = {},
                    onCheckNotifications = {},
                )

                runCurrent()

                assertTrue(
                    "Usage access dialog callback should be invoked without waiting for appSettings",
                    showUsageAccessDialogCalled,
                )
            }
        }

    @Test
    fun `checkPermissionsAndInit cancels previous job when new job is launched`() =
        runTest(testDispatcher) {
            val flow1 = MutableStateFlow(AppSettings(isLoaded = false))
            val flow2 = MutableStateFlow(AppSettings(isLoaded = false))

            var requested1 = false
            var requested2 = false

            controller.checkPermissionsAndInit(
                scope = this,
                isInitialSetupCompleted = isInitialSetupCompletedFlow,
                appSettingsLoaded = flow1,
                lastPermissionState = -1,
                onShowUsageAccessDialog = {},
                onRequestPhonePermission = { requested1 = true },
                onCheckNotifications = {},
            )

            controller.checkPermissionsAndInit(
                scope = this,
                isInitialSetupCompleted = isInitialSetupCompletedFlow,
                appSettingsLoaded = flow2,
                lastPermissionState = -1,
                onShowUsageAccessDialog = {},
                onRequestPhonePermission = { requested2 = true },
                onCheckNotifications = {},
            )

            // Emit on flow1, should be ignored because job was cancelled
            flow1.value = AppSettings(isLoaded = true, wasPhonePermissionRequestedBefore = false)
            runCurrent()
            assertFalse(requested1)

            // Emit on flow2, should trigger request2
            flow2.value = AppSettings(isLoaded = true, wasPhonePermissionRequestedBefore = false)
            runCurrent()
            assertTrue(requested2)
        }

    @Test
    fun `checkNotifications requests permission when not granted and settings loaded`() =
        runTest(testDispatcher) {
            whenever(permissionManager.hasNotificationPermission()).thenReturn(false)
            var notificationRequested = false

            controller.checkNotifications(
                scope = this,
                appSettingsLoaded =
                    MutableStateFlow(
                        AppSettings(isLoaded = true, notificationPermissionRequested = false),
                    ),
                autoRequest = true,
                onRequest = { notificationRequested = true },
            )

            runCurrent()
            assertTrue(notificationRequested)
        }
}
