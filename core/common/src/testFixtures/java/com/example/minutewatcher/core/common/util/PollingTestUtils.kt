package com.example.minutewatcher.core.common.util

import org.junit.Assert.assertTrue

/**
 * Polls a condition at fixed intervals until it evaluates to true or a timeout expires.
 *
 * @param timeoutMs Maximum duration in milliseconds to wait for the condition.
 * @param message Diagnostic message reported if the condition times out.
 * @param condition Function returning true when the expected condition is satisfied.
 */
fun pollUntil(
    timeoutMs: Long = 10000L,
    message: String,
    condition: () -> Boolean,
) {
    val maxIterations = (timeoutMs / 10).toInt().coerceAtLeast(1)
    repeat(maxIterations) {
        if (condition()) return
        Thread.sleep(10)
    }
    assertTrue("Timeout waiting for condition: $message", condition())
}
