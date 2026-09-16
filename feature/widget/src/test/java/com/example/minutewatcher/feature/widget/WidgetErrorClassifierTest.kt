package com.example.minutewatcher.feature.widget

import com.example.minutewatcher.core.common.util.initTestLogger
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
        val cause = android.os.TransactionTooLargeException()
        val e = IllegalArgumentException("Too large", cause)
        assertTrue(classifier.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns true when deeply nested cause is TransactionTooLargeException`() {
        val cause = android.os.TransactionTooLargeException()
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
        val cause = android.os.TransactionTooLargeException()
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
}
