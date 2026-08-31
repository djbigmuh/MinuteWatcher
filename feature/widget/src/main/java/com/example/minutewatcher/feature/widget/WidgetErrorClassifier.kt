package com.example.minutewatcher.feature.widget

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
            when (e) {
                is IllegalArgumentException -> {
                    if (isRemoteViewsLimitException(e)) {
                        logLimitExceeded(e, isFullAttempt)
                        UpdateResult.LimitExceeded
                    } else {
                        AppLogger.e("Widget update failed with IllegalArgumentException (not a size limit)", e)
                        UpdateResult.GenericFailure(isRetryable = true)
                    }
                }

                is IllegalStateException -> {
                    AppLogger.e("Widget update failed with IllegalStateException: ${e.message}", e)
                    UpdateResult.GenericFailure(isRetryable = false)
                }

                is SecurityException -> {
                    AppLogger.e("Security error updating widgets", e)
                    UpdateResult.GenericFailure(isRetryable = false)
                }

                is RuntimeException -> {
                    if (isRemoteViewsLimitException(e)) {
                        logLimitExceeded(e, isFullAttempt)
                        UpdateResult.LimitExceeded
                    } else {
                        AppLogger.e("Widget update failed with RuntimeException: ${e.message}", e)
                        UpdateResult.GenericFailure(isRetryable = true)
                    }
                }

                else -> {
                    AppLogger.e("Generic widget update failure: ${e.message}", e)
                    UpdateResult.GenericFailure(isRetryable = true)
                }
            }

        fun isRemoteViewsLimitException(e: Throwable): Boolean =
            hasCauseOfType(e, android.os.TransactionTooLargeException::class.java)

        private fun hasCauseOfType(
            e: Throwable,
            type: Class<out Throwable>,
        ): Boolean {
            var current: Throwable? = e
            while (current != null) {
                if (type.isInstance(current)) return true
                current = current.cause
            }
            return false
        }

        private fun logLimitExceeded(
            e: Exception,
            isFullAttempt: Boolean,
        ) {
            if (isFullAttempt) {
                AppLogger.e("Widget update failed due to size limit", e)
            } else {
                AppLogger.e("CRITICAL: Widget size limit exceeded even with simplified layout!", e)
            }
        }
    }
