package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.PermissionStateNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionStateNotifierImpl
    @Inject
    constructor() : PermissionStateNotifier {
        private val _changes = MutableStateFlow(0)

        override val changes: Flow<Unit> = _changes.map { }

        override fun notifyChanged() {
            _changes.value++
        }
    }
