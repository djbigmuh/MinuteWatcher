package com.example.minutewatcher.feature.widget

import android.os.TransactionTooLargeException
import com.example.minutewatcher.core.common.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies widget update errors, specifically identifying RemoteViews size limits.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class WidgetErrorClassifier
    @Inject
    constructor() {
        fun classifyUpdateError(
            e: Exception,
            isFullAttempt: Boolean,
        ): UpdateResult =
            when {
                isRemoteViewsLimitException(e) -> {
                    logLimitExceeded(e, isFullAttempt)
                    UpdateResult.LimitExceeded(wasFull = isFullAttempt)
                }

                e is SecurityException -> {
                    AppLogger.e("Security error updating widgets", e)
                    UpdateResult.GenericFailure(isRetryable = false)
                }

                e is IllegalStateException -> {
                    AppLogger.e("Widget update failed with IllegalStateException: ${e.message}", e)
                    UpdateResult.GenericFailure(isRetryable = false)
                }

                e is IllegalArgumentException -> {
                    AppLogger.e("Widget update failed with IllegalArgumentException (not a size limit)", e)
                    UpdateResult.GenericFailure(isRetryable = true)
                }

                e is RuntimeException -> {
                    AppLogger.e("Widget update failed with RuntimeException: ${e.message}", e)
                    UpdateResult.GenericFailure(isRetryable = true)
                }

                else -> {
                    AppLogger.e("Generic widget update failure: ${e.message}", e)
                    UpdateResult.GenericFailure(isRetryable = true)
                }
            }

        fun isRemoteViewsLimitException(e: Throwable): Boolean =
            hasCauseOfType(e, TransactionTooLargeException::class.java)

        private fun hasCauseOfType(
            e: Throwable,
            type: Class<out Throwable>,
        ): Boolean {
            var current: Throwable? = e
            var depth = 0
            val visited = mutableSetOf<Throwable>()
            while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
                if (type.isInstance(current)) return true
                current = current.cause
                depth++
            }
            return false
        }

        private fun logLimitExceeded(
            e: Throwable,
            isFullAttempt: Boolean,
        ) {
            if (isFullAttempt) {
                AppLogger.e("Widget update failed due to size limit", e)
            } else {
                AppLogger.e("CRITICAL: Widget size limit exceeded even with simplified layout!", e)
            }
        }

        private companion object {
            private const val MAX_CAUSE_DEPTH = 16
        }
    }
