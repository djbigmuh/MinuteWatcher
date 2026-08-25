package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CancellationException

/**
 * Utility function to execute a block and catch exceptions while ensuring
 * that [CancellationException] is always rethrown.
 *
 * @param block The suspend block to execute.
 * @param onError Callback for handling non-cancellation exceptions.
 * @return The result of the block or null if an exception occurred.
 */
suspend inline fun <T> catchingCancellation(
    crossinline block: suspend () -> T,
    crossinline onError: suspend (Exception) -> Unit,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
        null
    }
