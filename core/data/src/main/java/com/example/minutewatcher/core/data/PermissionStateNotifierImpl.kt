package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.PermissionStateNotifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionStateNotifierImpl
    @Inject
    constructor() : PermissionStateNotifier {
        private val _changes =
            MutableSharedFlow<Unit>(
                replay = 1,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            ).apply {
                tryEmit(Unit)
            }

        override val changes = _changes.asSharedFlow()

        override fun notifyChanged() {
            _changes.tryEmit(Unit)
        }
    }
