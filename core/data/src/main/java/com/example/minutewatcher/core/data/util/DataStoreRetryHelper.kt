package com.example.minutewatcher.core.data.util

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Helper class for retrying DataStore read operations with exponential backoff.
 *
 * **Thread safety:** Safe to call from any coroutine context.
 */
@Singleton
open class DataStoreRetryHelper
    @Inject
    constructor() {
        private class Emission<T>(
            val value: T,
        )

        /**
         * Reads from DataStore with retries on failure or timeout.
         *
         * @param label A label for logging purposes.
         * @param attempts Number of retry attempts.
         * @param retryDelayMs Initial delay between retries.
         * @param attemptTimeoutMillis Timeout for each read attempt.
         * @param reader Lambda that returns a Flow to read from.
         * @return Result containing the value or the last exception.
         */
        open suspend fun <T> readWithRetry(
            label: String,
            attempts: Int = DEFAULT_RETRY_ATTEMPTS,
            retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
            attemptTimeoutMillis: Long = Constants.Flow.RETRY_READ_ATTEMPT_TIMEOUT_MS,
            reader: () -> Flow<T>,
        ): Result<T> {
            var lastException: Exception? = null
            var currentDelay = retryDelayMs

            for (attempt in 1..attempts) {
                try {
                    val emission =
                        withTimeoutOrNull(attemptTimeoutMillis.milliseconds) {
                            reader().map { Emission(it) }.firstOrNull()
                        }
                    if (emission != null) return Result.success(emission.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                }

                if (attempt < attempts) {
                    AppLogger.w(
                        "DataStore read retry ($label): attempt $attempt/$attempts failed. " +
                            "Retrying in ${currentDelay}ms",
                    )
                    delay(currentDelay.milliseconds)
                    currentDelay *= 2
                }
            }
            return Result.failure(lastException ?: IOException("DataStore read Timeout ($label)"))
        }

        companion object {
            const val DEFAULT_RETRY_ATTEMPTS = 3
            const val DEFAULT_RETRY_DELAY_MS = 500L
        }
    }
