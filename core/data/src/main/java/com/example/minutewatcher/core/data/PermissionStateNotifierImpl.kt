package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.PermissionStateNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionStateNotifierImpl
    @Inject
    constructor() : PermissionStateNotifier {
        private val _changes = MutableStateFlow(Unit)

        override val changes = _changes.asStateFlow()

        override fun notifyChanged() {
            _changes.value = Unit
        }
    }
