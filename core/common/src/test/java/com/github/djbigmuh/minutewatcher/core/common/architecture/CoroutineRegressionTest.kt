package com.github.djbigmuh.minutewatcher.core.common.architecture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineRegressionTest {
    @Test
    fun `CancellationException is propagated through try-catch blocks`() =
        runTest {
            var cancellationCaught = false
            var otherCaught = false

            try {
                try {
                    throw CancellationException("Operation cancelled")
                } catch (e: CancellationException) {
                    cancellationCaught = true
                    throw e
                } catch (_: Exception) {
                    otherCaught = true
                }
            } catch (_: CancellationException) {
                // Outer catch of cancellation
            }

            assertTrue(
                "CancellationException should be caught by CancellationException handler",
                cancellationCaught,
            )
            assertFalse(
                "General exception handler should not be reached for CancellationException",
                otherCaught,
            )
        }

    @Test
    fun `SupervisorJob isolates child coroutine errors`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val handler = CoroutineExceptionHandler { _, _ -> }
            val supervisorScope = CoroutineScope(SupervisorJob() + dispatcher + handler)

            var siblingExecuted = false

            supervisorScope.launch {
                throw IllegalStateException("Failing child")
            }

            supervisorScope.launch {
                delay(100.milliseconds)
                siblingExecuted = true
            }

            advanceUntilIdle()

            assertTrue(
                "Sibling coroutine should execute successfully despite sibling failure under SupervisorJob",
                siblingExecuted,
            )
            assertTrue(
                "Supervisor scope should remain active",
                supervisorScope.coroutineContext[Job]?.isActive == true,
            )
        }

    @Test
    fun `Scope cancellation cancels active child jobs`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            var jobCanceled = false

            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    suspendCancellableCoroutine<Unit> { }
                } catch (e: CancellationException) {
                    jobCanceled = true
                    throw e
                }
            }

            scope.cancel()
            advanceUntilIdle()

            assertTrue(
                "Active child job should be canceled when scope is canceled",
                jobCanceled,
            )
        }
}
