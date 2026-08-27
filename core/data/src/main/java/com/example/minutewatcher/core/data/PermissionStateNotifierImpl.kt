package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.PermissionStateNotifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [PermissionStateNotifier] for broadcasting permission change events.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class PermissionStateNotifierImpl
    @Inject
    constructor() : PermissionStateNotifier {
        // PermissionStateNotifier is responsible ONLY for observing permission state changes
        // and emitting UI events. All permission checks are delegated to PermissionManager
        // to avoid duplication and ensure consistency.

    private val _changes =
        MutableSharedFlow<Unit>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /**
         * Flow of permission change events.
         *
         * Safe to call from any coroutine context.
         */
        override val changes: Flow<Unit> = _changes.asSharedFlow()

        /**
         * Notifies subscribers that a permission state has changed.
         *
         * Safe to call from any coroutine context.
         */
        override fun notifyChanged() {
            _changes.tryEmit(Unit)
        }
    }
