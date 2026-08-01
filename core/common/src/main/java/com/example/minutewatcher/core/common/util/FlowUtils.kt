package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Utility for WorkManager workers and ViewModels to safely fetch the first value from a Flow with a timeout.
 * This prevents tasks from hanging indefinitely if a Flow never emits.
 */
suspend fun <T> Flow<T>.firstWithTimeout(timeoutMillis: Long = 5000L): T? {
    return withTimeoutOrNull(timeoutMillis.milliseconds) {
        this@firstWithTimeout.first()
    }
}

/**
 * Overload that accepts [Duration].
 */
suspend fun <T> Flow<T>.firstWithTimeout(timeout: Duration): T? {
    return withTimeoutOrNull(timeout) {
        this@firstWithTimeout.first()
    }
}
