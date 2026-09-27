package com.example.minutewatcher.controller

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.WorkScheduler
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.feature.main.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainPermissionControllerTest {
    private val permissionManager: PermissionManager = mock()
    private val workScheduler: WorkScheduler = mock()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var controller: MainPermissionController

    private val viewModel: MainViewModel = mock()
    private val appSettingsFlow = MutableStateFlow(AppSettings(isLoaded = false))

    @Before
    fun setup() {
        initTestLogger()
        controller = MainPermissionController(permissionManager, workScheduler, testDispatcher)
        whenever(viewModel.appSettings).thenReturn(appSettingsFlow)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
        whenever(viewModel.isInitialSetupCompleted).thenReturn(MutableStateFlow(false))
    }

    @Test
    fun `checkPermissionsAndInit waits for isLoaded before requesting phone permission`() =
        runTest(testDispatcher) {
            var phoneRequested = false

            controller.checkPermissionsAndInit(
                scope = this,
                viewModel = viewModel,
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
                viewModel = viewModel,
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
            // Never emits loaded settings
            whenever(viewModel.appSettings).thenReturn(MutableStateFlow(AppSettings(isLoaded = false)))

            var phoneRequested = false
            controller.checkPermissionsAndInit(
                scope = this,
                viewModel = viewModel,
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

            // never completes setup check
            whenever(viewModel.isInitialSetupCompleted).thenReturn(flow { awaitCancellation() })

            controller.checkPermissionsAndInit(
                scope = this,
                viewModel = viewModel,
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
                whenever(viewModel.appSettings).thenReturn(MutableStateFlow(AppSettings(isLoaded = false)))

                var showUsageAccessDialogCalled = false

                controller.checkPermissionsAndInit(
                    scope = this,
                    viewModel = viewModel,
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
}
