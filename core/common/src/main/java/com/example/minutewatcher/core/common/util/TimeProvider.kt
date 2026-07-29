package com.example.minutewatcher.core.common.util

import javax.inject.Inject
import javax.inject.Singleton

interface TimeProvider {
    fun now(): Long
    fun elapsedRealtime(): Long
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
    override fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}
