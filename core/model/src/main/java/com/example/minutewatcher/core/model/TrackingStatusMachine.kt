package com.example.minutewatcher.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Единая машина переходов TrackingStatus.
 * Приоритет: PERMISSION_MISSING > DATABASE_ERROR > TRANSIENT(UNKNOWN/NOT_SUPPORTED/
 * INACCURATE_ATTRIBUTION) > HEALTHY(Active/Idle/Ready) > INDEXING.
 *
 * Правила:
 * 1. force — безусловная установка.
 * 2. newPrio >= currentPrio — установка.
 * 3. Recovery: HEALTHY снимает TRANSIENT-ошибку (не снимает sticky DATABASE/PERMISSION).
 * 4. Иначе текущий статус сохраняется.
 */
class TrackingStatusMachine(initial: TrackingStatus = TrackingStatus.Idle) {
    private val _status = MutableStateFlow(initial)
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()
    val current: TrackingStatus get() = _status.value

    fun transition(newStatus: TrackingStatus, force: Boolean = false) {
        _status.update { current -> merge(current, newStatus, force) }
    }

    companion object {
        fun priority(status: TrackingStatus): Int =
            when (status) {
                is TrackingStatus.Error ->
                    when (status.type) {
                        TrackingStatus.ErrorType.PERMISSION_MISSING -> TrackingStatus.PRIORITY_PERMISSION_MISSING
                        TrackingStatus.ErrorType.DATABASE_ERROR -> TrackingStatus.PRIORITY_DATABASE_ERROR
                        else -> TrackingStatus.PRIORITY_TRANSIENT_ERROR
                    }

                is TrackingStatus.Active, is TrackingStatus.Idle, is TrackingStatus.Ready ->
                    TrackingStatus.PRIORITY_HEALTHY

                TrackingStatus.Indexing -> TrackingStatus.PRIORITY_INDEXING
            }

        fun merge(
            current: TrackingStatus,
            new: TrackingStatus,
            force: Boolean,
        ): TrackingStatus =
            when {
                force -> new
                current == new -> current
                priority(new) >= priority(current) -> new
                (priority(new) == TrackingStatus.PRIORITY_HEALTHY) &&
                    (priority(current) == TrackingStatus.PRIORITY_TRANSIENT_ERROR) -> new

                else -> current
            }
    }
}

/**
 * Returns true if the error is "sticky" and should not be cleared by regular
 * non-forced status refreshes. Sticky errors usually require manual recovery
 * or a specific fix (like granting permissions).
 */
fun TrackingStatus.ErrorType.isSticky(): Boolean =
    when (this) {
        TrackingStatus.ErrorType.PERMISSION_MISSING,
        TrackingStatus.ErrorType.DATABASE_ERROR,
        -> true

        TrackingStatus.ErrorType.UNKNOWN,
        TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
        TrackingStatus.ErrorType.NOT_SUPPORTED,
        -> false
    }
