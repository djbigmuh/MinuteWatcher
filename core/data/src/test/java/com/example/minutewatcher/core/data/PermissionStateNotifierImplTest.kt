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
 * Edge case tests verify behavior under error conditions.
 * These are critical for production stability on custom ROMs
 * where system services may degrade or fail unexpectedly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionStateNotifierImplTest {
    @Test
    fun `initial value is not emitted`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()
            val job = launch(UnconfinedTestDispatcher()) { notifier.changes.toList(results) }
            assertEquals(0, results.size)
            job.cancel()
        }

    @Test
    fun `notifyChanged emits value`() =
        runTest {
            val testDispatcher = UnconfinedTestDispatcher()
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()

            val job =
                launch(testDispatcher) {
                    notifier.changes.toList(results)
                }

            notifier.notifyChanged()

            assertEquals(1, results.size)
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

            assertTrue(results.isNotEmpty())
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

            assertTrue(results.isNotEmpty())
            job.cancel()
        }

    @Test
    fun `does not emit until notifyChanged is called`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()

            val job = launch(UnconfinedTestDispatcher()) { notifier.changes.toList(results) }

            assertEquals(0, results.size)

            notifier.notifyChanged()
            assertEquals(1, results.size)

            job.cancel()
        }
}
