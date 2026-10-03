package com.example.minutewatcher.core.common.util

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

interface TimeProvider {
    fun now(): Long

    fun elapsedRealtime(): Long

    fun uptimeMillis(): Long
}

@Singleton
class SystemTimeProvider
    @Inject
    constructor() : TimeProvider {
        override fun now(): Long = System.currentTimeMillis()

        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

        override fun uptimeMillis(): Long = SystemClock.uptimeMillis()
    }
