package com.example.minutewatcher.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PermissionStateNotifierImpl].
 *
 * Safe to call from any coroutine context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionStateNotifierImplTest {
    @Test
    fun `initial value is emitted immediately`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()
            val job = launch(UnconfinedTestDispatcher()) { notifier.changes.toList(results) }
            assertEquals(1, results.size)
            job.cancel()
        }

    @Test
    fun `notifyChanged emits new value`() =
        runTest {
            val testDispatcher = UnconfinedTestDispatcher()
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()

            val job =
                launch(testDispatcher) {
                    notifier.changes.toList(results)
                }

            // 1 emission initially
            assertEquals(1, results.size)

            notifier.notifyChanged()

            // 2 emissions total
            assertEquals(2, results.size)
            job.cancel()
        }

    @Test
    fun `emits state change when permission is granted`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()
            val job = launch(UnconfinedTestDispatcher()) { notifier.changes.toList(results) }

            // Simulate permission grant
            notifier.notifyChanged()

            assertTrue(results.size >= 2)
            job.cancel()
        }

    @Test
    fun `emits state change when permission is revoked`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()
            val job = launch(UnconfinedTestDispatcher()) { notifier.changes.toList(results) }

            // Simulate permission revoke
            notifier.notifyChanged()

            assertTrue(results.size >= 2)
            job.cancel()
        }

    @Test
    fun `emits multiple notifications correctly`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()

            val job = launch(UnconfinedTestDispatcher()) { notifier.changes.toList(results) }

            assertEquals(1, results.size)

            notifier.notifyChanged()
            assertEquals(2, results.size)

            notifier.notifyChanged()
            assertEquals(3, results.size)

            job.cancel()
        }
}
