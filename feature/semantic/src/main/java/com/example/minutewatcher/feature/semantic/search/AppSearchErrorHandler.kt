package com.example.minutewatcher.feature.semantic.search

import android.os.DeadObjectException
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicReference
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
    constructor(
        private val timeProvider: TimeProvider,
    ) {
        private val failureState = AtomicReference(0 to 0L)
        val consecutiveIndexFailures: Int get() = failureState.get().first

        fun resetFailures() {
            failureState.set(0 to 0L)
        }

        fun handleSchemaException(
            e: AppSearchException,
            forceOverride: Boolean,
            status: MutableStateFlow<TrackingStatus>,
        ) {
            if ((e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) && !forceOverride) {
                return
            }

            val isNotSupported = e.resultCode == AppSearchResult.RESULT_INTERNAL_ERROR

            if (isNotSupported) {
                AppLogger.w("AppSearch is not supported or encountered internal error (code=${e.resultCode}).", e)
                status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.NOT_SUPPORTED,
                        "AppSearch service not available or failed",
                    )
            } else {
                AppLogger.e("AppSearch schema setup failed (force=$forceOverride, code=${e.resultCode})", e)
                status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
            }
        }

        fun handleInitializationError(
            e: Exception,
            status: MutableStateFlow<TrackingStatus>,
        ) {
            if (e is CancellationException) throw e

            val isNotSupported =
                hasCause(e, IllegalStateException::class.java) ||
                    hasCause(e, DeadObjectException::class.java) ||
                    hasCause(e, SecurityException::class.java) ||
                    (e is AppSearchException && e.resultCode == AppSearchResult.RESULT_INTERNAL_ERROR)

            if (isNotSupported) {
                AppLogger.w("AppSearch is not supported or service failed on this device.", e)
                status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.NOT_SUPPORTED,
                        "AppSearch service not available on this device",
                    )
            } else {
                AppLogger.e("Fatal AppSearch initialization error", e)
                status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            }
        }

        fun handleIndexFailure(status: MutableStateFlow<TrackingStatus>) {
            val suppressedMessage = "Indexing suppressed after $MAX_CONSECUTIVE_INDEX_FAILURES failures"

            // Idempotency check: if status is already suppressed Error, do nothing.
            val currentStatus = status.value
            if (currentStatus is TrackingStatus.Error &&
                currentStatus.type == TrackingStatus.ErrorType.DATABASE_ERROR &&
                currentStatus.message == suppressedMessage
            ) {
                return
            }

            val newState =
                failureState.updateAndGet { (count, _) ->
                    (count + 1) to timeProvider.elapsedRealtime()
                }
            val failures = newState.first
            if (failures >= MAX_CONSECUTIVE_INDEX_FAILURES) {
                AppLogger.e(
                    "SemanticSearchManager: $failures consecutive indexing failures. " +
                        "Further attempts suppressed until next app restart.",
                )
                status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        suppressedMessage,
                    )
            }
        }

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
            while (current != null) {
                if (targetClass.isInstance(current)) return true
                current = current.cause
            }
            return false
        }

        companion object {
            const val MAX_CONSECUTIVE_INDEX_FAILURES = 5
        }
    }
