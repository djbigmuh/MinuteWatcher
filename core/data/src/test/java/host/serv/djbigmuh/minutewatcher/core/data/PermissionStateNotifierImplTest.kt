package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [PermissionStateNotifierImpl].
 *
 * Safe to call from any coroutine context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionStateNotifierImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial value is emitted immediately`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()
            val job = launch { notifier.changes.toList(results) }
            runCurrent()
            assertEquals(1, results.size)
            job.cancel()
        }

    @Test
    fun `notifyChanged emits new value`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()

            val job =
                launch {
                    notifier.changes.toList(results)
                }
            runCurrent()

            // 1 emission initially
            assertEquals(1, results.size)

            notifier.notifyChanged()
            runCurrent()

            // 2 emissions total
            assertEquals(2, results.size)
            job.cancel()
        }

    @Test
    fun `emits state change when permission is granted`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()
            val job = launch { notifier.changes.toList(results) }
            runCurrent()

            // Simulate permission grant
            notifier.notifyChanged()
            runCurrent()

            assertTrue(results.size >= 2)
            job.cancel()
        }

    @Test
    fun `emits state change when permission is revoked`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()
            val job = launch { notifier.changes.toList(results) }
            runCurrent()

            // Simulate permission revoke
            notifier.notifyChanged()
            runCurrent()

            assertTrue(results.size >= 2)
            job.cancel()
        }

    @Test
    fun `emits multiple notifications correctly`() =
        runTest {
            val notifier = PermissionStateNotifierImpl()
            val results = mutableListOf<Unit>()

            val job = launch { notifier.changes.toList(results) }
            runCurrent()

            assertEquals(1, results.size)

            notifier.notifyChanged()
            runCurrent()
            assertEquals(2, results.size)

            notifier.notifyChanged()
            runCurrent()
            assertEquals(3, results.size)

            job.cancel()
        }
}
