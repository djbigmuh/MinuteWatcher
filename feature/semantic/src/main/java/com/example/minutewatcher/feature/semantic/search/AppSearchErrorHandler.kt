package com.example.minutewatcher.feature.semantic.search

import android.os.DeadObjectException
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles AppSearch error classification and status management.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class AppSearchErrorHandler
    @Inject
    constructor() {
        private val _consecutiveIndexFailures = AtomicInteger(0)
        val consecutiveIndexFailures: Int get() = _consecutiveIndexFailures.get()

        fun resetFailures() {
            _consecutiveIndexFailures.set(0)
        }

        fun handleSchemaException(
            e: AppSearchException,
            forceOverride: Boolean,
            currentStatus: TrackingStatus,
        ): TrackingStatus {
            if ((e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) && !forceOverride) {
                return currentStatus
            }

            val isNotSupported = e.resultCode == AppSearchResult.RESULT_INTERNAL_ERROR

            return if (isNotSupported) {
                AppLogger.w("AppSearch is not supported or encountered internal error (code=${e.resultCode}).", e)
                TrackingStatus.Error(
                    type = TrackingStatus.ErrorType.NOT_SUPPORTED,
                    message = "AppSearch service not available or failed",
                    code = TrackingStatus.ErrorCode.SERVICE_UNAVAILABLE,
                )
            } else {
                AppLogger.e("AppSearch schema setup failed (force=$forceOverride, code=${e.resultCode})", e)
                TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
            }
        }

        fun handleInitializationError(e: Exception): TrackingStatus {
            if (e is CancellationException) {
                throw e
            }

            if (hasCause(e, SecurityException::class.java)) {
                return TrackingStatus.Error(
                    type = TrackingStatus.ErrorType.PERMISSION_MISSING,
                    message = "Permission missing for AppSearch",
                )
            }

            val isNotSupported =
                hasCause(e, DeadObjectException::class.java) ||
                    hasCause(e, UnsupportedOperationException::class.java) ||
                    (e is IllegalStateException) ||
                    (e is AppSearchException && (e.resultCode == AppSearchResult.RESULT_INTERNAL_ERROR))

            return if (isNotSupported) {
                AppLogger.w("AppSearch is not supported or service failed on this device.", e)
                TrackingStatus.Error(
                    type = TrackingStatus.ErrorType.NOT_SUPPORTED,
                    message = "AppSearch service not available on this device",
                    code = TrackingStatus.ErrorCode.SERVICE_UNAVAILABLE,
                )
            } else {
                AppLogger.e("Fatal AppSearch initialization error", e)
                TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            }
        }

        fun handleIndexFailure(
            currentStatus: TrackingStatus,
            errorOverride: String? = null,
        ): TrackingStatus {
            // Idempotency check: if status is already NOT_SUPPORTED, do nothing.
            if (currentStatus is TrackingStatus.Error &&
                currentStatus.type == TrackingStatus.ErrorType.NOT_SUPPORTED
            ) {
                return currentStatus
            }

            val isSameErrorType =
                currentStatus is TrackingStatus.Error &&
                    currentStatus.type == TrackingStatus.ErrorType.DATABASE_ERROR

            val failures =
                if (isSameErrorType) {
                    _consecutiveIndexFailures.incrementAndGet()
                } else {
                    _consecutiveIndexFailures.set(1)
                    1
                }

            val baseMessage = errorOverride ?: "Indexing failed"
            val failureMessage = "Indexing failed after $MAX_CONSECUTIVE_INDEX_FAILURES consecutive errors"

            return if (failures >= MAX_CONSECUTIVE_INDEX_FAILURES) {
                AppLogger.e(
                    "AppSearchErrorHandler: $failures consecutive indexing failures. " +
                        "Status set to Error, but future snapshots will still be attempted.",
                )
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.DATABASE_ERROR,
                    failureMessage,
                )
            } else {
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.DATABASE_ERROR,
                    if (errorOverride != null) {
                        baseMessage
                    } else {
                        "Indexing failed (attempt $failures/$MAX_CONSECUTIVE_INDEX_FAILURES)"
                    },
                )
            }
        }

        // ThrowsCount: throwIfErrors throws specific exceptions depending on which operations failed.
        @Suppress("ThrowsCount")
        fun throwIfErrors(
            putEx: Exception?,
            removeEx: Exception?,
        ) {
            if (putEx != null || removeEx != null) {
                if (putEx is AppSearchException && removeEx == null) throw putEx
                if (removeEx is AppSearchException && putEx == null) throw removeEx

                val message =
                    when {
                        putEx != null && removeEx != null ->
                            "put failed: ${putEx.message}; remove failed: ${removeEx.message}"
                        putEx != null -> "put failed: ${putEx.message}"
                        else -> "remove failed: ${removeEx?.message}"
                    }
                throw AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR, message)
            }
        }

        private fun hasCause(
            e: Throwable,
            targetClass: Class<out Throwable>,
        ): Boolean {
            var current: Throwable? = e
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (targetClass.isInstance(current)) return true
                current = current.cause
                depth++
            }
            return false
        }

        companion object {
            const val MAX_CONSECUTIVE_INDEX_FAILURES = 5
            private const val MAX_CAUSE_DEPTH = 16
        }
    }
