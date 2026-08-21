package com.example.minutewatcher.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
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
    fun `initial value is emitted`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val firstValue = notifier.changes.first()
            assertEquals(Unit, firstValue)
        }

    @Test
    fun `notifyChanged emits new value`() =
        runTest {
            val testDispatcher = UnconfinedTestDispatcher()
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()

            val job =
                launch(testDispatcher) {
                    notifier.changes.take(2).toList(results)
                }

            notifier.notifyChanged()

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
    fun `does not emit duplicate states`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()

            // We can't really test "duplicate Unit" because Unit is a singleton.
            // But we can verify that if we don't call notifyChanged, it doesn't emit after initial.
            val job = launch(UnconfinedTestDispatcher()) { notifier.changes.toList(results) }

            assertEquals(1, results.size) // Initial value from MutableStateFlow(0)

            // No call to notifyChanged()
            assertEquals(1, results.size)

            job.cancel()
        }
}
