package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val permissionManager: PermissionManager = mock()
    private val notifier = PermissionStateNotifierImpl()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var repository: PermissionRepositoryImpl

    @Before
    fun setup() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)
        repository = PermissionRepositoryImpl(permissionManager, notifier, testScope)
    }

    @Test
    fun `phoneStatePermissionGranted emits initial value and updates when notifier fires`() =
        runTest {
            assertTrue(repository.phoneStatePermissionGranted.value)

            val emissions = mutableListOf<Boolean>()
            val job =
                launch(UnconfinedTestDispatcher()) {
                    repository.phoneStatePermissionGranted.toList(emissions)
                }
            runCurrent()

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            notifier.notifyChanged()
            runCurrent()

            assertFalse(repository.phoneStatePermissionGranted.value)
            assertEquals(listOf(true, false), emissions)
            job.cancel()
        }

    @Test
    fun `usageStatsPermissionGranted emits initial value and updates when notifier fires`() =
        runTest {
            assertTrue(repository.usageStatsPermissionGranted.value)

            val emissions = mutableListOf<Boolean>()
            val job =
                launch(UnconfinedTestDispatcher()) {
                    repository.usageStatsPermissionGranted.toList(emissions)
                }
            runCurrent()

            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(false)
            notifier.notifyChanged()
            runCurrent()

            assertFalse(repository.usageStatsPermissionGranted.value)
            assertEquals(listOf(true, false), emissions)
            job.cancel()
        }

    @Test
    fun `notificationPermissionGranted emits initial value and updates when notifier fires`() =
        runTest {
            assertTrue(repository.notificationPermissionGranted.value)

            val emissions = mutableListOf<Boolean>()
            val job =
                launch(UnconfinedTestDispatcher()) {
                    repository.notificationPermissionGranted.toList(emissions)
                }
            runCurrent()

            whenever(permissionManager.hasNotificationPermission()).thenReturn(false)
            notifier.notifyChanged()
            runCurrent()

            assertFalse(repository.notificationPermissionGranted.value)
            assertEquals(listOf(true, false), emissions)
            job.cancel()
        }
}
