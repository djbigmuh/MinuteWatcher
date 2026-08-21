package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Notifier for permission state changes.
 *
 * Safe to use from any thread.
 */
interface PermissionStateNotifier {
    /**
     * Flow that emits a new value whenever permissions might have changed.
     * Emits initial value immediately.
     */
    val changes: Flow<Unit>

    /**
     * Notify that a permission state change might have occurred.
     */
    fun notifyChanged()
}
