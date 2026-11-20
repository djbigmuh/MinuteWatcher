package com.github.djbigmuh.minutewatcher.feature.widget

import android.os.TransactionTooLargeException
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WidgetErrorClassifierTest {
    private val classifier = WidgetErrorClassifier()

    @Before
    fun setup() {
        initTestLogger()
    }

    @Test
    fun `isRemoteViewsLimitException returns true when cause is TransactionTooLargeException`() {
        val cause = TransactionTooLargeException()
        val e = IllegalArgumentException("Too large", cause)
        assertTrue(classifier.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns true when deeply nested cause is TransactionTooLargeException`() {
        val cause = TransactionTooLargeException()
        val inner = RuntimeException("Inner", cause)
        val e = IllegalArgumentException("Outer", inner)
        assertTrue(classifier.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns false for other exceptions`() {
        val e = IllegalArgumentException("Other error")
        assertFalse(classifier.isRemoteViewsLimitException(e))
    }

    @Test
    fun `classifyUpdateError returns LimitExceeded for TransactionTooLargeException`() {
        val cause = TransactionTooLargeException()
        val e = IllegalArgumentException("Too large", cause)

        val result = classifier.classifyUpdateError(e, isFullAttempt = true)

        assertEquals(UpdateResult.LimitExceeded(wasFull = true), result)
    }

    @Test
    fun `classifyUpdateError returns GenericFailure retryable for generic IllegalArgumentException`() {
        val e = IllegalArgumentException("Generic")

        val result = classifier.classifyUpdateError(e, isFullAttempt = true)

        assertTrue(result is UpdateResult.GenericFailure)
        assertTrue((result as UpdateResult.GenericFailure).isRetryable)
    }

    @Test
    fun `classifyUpdateError returns GenericFailure non-retryable for IllegalStateException`() {
        val e = IllegalStateException("Bad state")

        val result = classifier.classifyUpdateError(e, isFullAttempt = true)

        assertTrue(result is UpdateResult.GenericFailure)
        assertFalse((result as UpdateResult.GenericFailure).isRetryable)
    }

    @Test
    fun `classifyUpdateError returns GenericFailure retryable for unknown exception`() {
        val e = Exception("Unknown")

        val result = classifier.classifyUpdateError(e, isFullAttempt = true)

        assertTrue(result is UpdateResult.GenericFailure)
        assertTrue((result as UpdateResult.GenericFailure).isRetryable)
    }

    @Test
    fun `classifyUpdateError returns LimitExceeded for direct TransactionTooLargeException`() {
        val e = TransactionTooLargeException()
        val result1 = classifier.classifyUpdateError(e, isFullAttempt = true)
        assertEquals(UpdateResult.LimitExceeded(wasFull = true), result1)

        val result2 = classifier.classifyUpdateError(e, isFullAttempt = false)
        assertEquals(UpdateResult.LimitExceeded(wasFull = false), result2)
    }

    @Test
    fun `classifyUpdateError returns LimitExceeded when cause of RuntimeException is TransactionTooLargeException`() {
        val e = RuntimeException("Runtime", TransactionTooLargeException())
        val result = classifier.classifyUpdateError(e, isFullAttempt = true)
        assertEquals(UpdateResult.LimitExceeded(wasFull = true), result)
    }

    @Test
    fun `classifyUpdateError returns LimitExceeded when cause of IllegalStateException`() {
        val e =
            IllegalStateException(
                "Illegal state",
                TransactionTooLargeException(),
            )
        val result = classifier.classifyUpdateError(e, isFullAttempt = true)
        assertEquals(UpdateResult.LimitExceeded(wasFull = true), result)
    }

    @Test
    fun `classifyUpdateError returns LimitExceeded when cause of Exception is TransactionTooLargeException`() {
        val e = Exception("General exception", TransactionTooLargeException())
        val result = classifier.classifyUpdateError(e, isFullAttempt = true)
        assertEquals(UpdateResult.LimitExceeded(wasFull = true), result)
    }

    @Test
    fun `isRemoteViewsLimitException handles cause at MAX_CAUSE_DEPTH minus one`() {
        var current: Throwable = TransactionTooLargeException()
        repeat(14) {
            current = RuntimeException("Depth", current)
        }
        assertTrue(classifier.isRemoteViewsLimitException(current))
    }

    @Test
    fun `isRemoteViewsLimitException handles cause beyond MAX_CAUSE_DEPTH`() {
        var current: Throwable = TransactionTooLargeException()
        repeat(20) {
            current = RuntimeException("Depth", current)
        }
        assertFalse(classifier.isRemoteViewsLimitException(current))
    }

    @Test(timeout = 3000)
    fun `isRemoteViewsLimitException handles cyclic cause chain safely`() {
        val t1 = RuntimeException("t1")
        val t2 = RuntimeException("t2")
        t1.initCause(t2)
        try {
            t2.initCause(t1)
        } catch (_: Exception) {
        }
        assertFalse(classifier.isRemoteViewsLimitException(t1))
    }

    @Test
    fun `classifyUpdateError returns GenericFailure non-retryable for SecurityException without limit exception`() {
        val e = SecurityException("No access")
        val result = classifier.classifyUpdateError(e, isFullAttempt = true)
        assertTrue(result is UpdateResult.GenericFailure)
        assertFalse((result as UpdateResult.GenericFailure).isRetryable)
    }

    @Test
    fun `classifyUpdateError returns GenericFailure retryable for IOException`() {
        val e = java.io.IOException("IO error")
        val result = classifier.classifyUpdateError(e, isFullAttempt = true)
        assertTrue(result is UpdateResult.GenericFailure)
        assertTrue((result as UpdateResult.GenericFailure).isRetryable)
    }
}
