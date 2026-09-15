package com.example.minutewatcher.controller

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.data.WorkScheduler
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.feature.main.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainPermissionControllerTest {
    private val permissionManager: PermissionManager = mock()
    private val workScheduler: WorkScheduler = mock()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var controller: MainPermissionController

    private val viewModel: MainViewModel = mock()
    private val appSettingsFlow = MutableStateFlow(AppSettings(isLoaded = false))

    @Before
    fun setup() {
        controller = MainPermissionController(permissionManager, workScheduler, testDispatcher)
        whenever(viewModel.appSettings).thenReturn(appSettingsFlow)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
        whenever(viewModel.isInitialSetupCompleted).thenReturn(MutableStateFlow(false))
    }

    @Test
    fun `checkPermissionsAndInit waits for isLoaded before requesting phone permission`() =
        runTest {
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
        runTest {
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

            runCurrent()
            verify(workScheduler).scheduleInitialSetup(any())
            assertTrue("Should check notifications if phone and usage granted", notificationsChecked)
        }
}
