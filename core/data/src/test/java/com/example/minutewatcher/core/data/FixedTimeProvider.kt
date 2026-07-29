package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.TimeProvider

class FixedTimeProvider(
    private var currentTime: Long = 0L,
    private var currentRealtime: Long = 0L
) : TimeProvider {
    override fun now(): Long = currentTime
    override fun elapsedRealtime(): Long = currentRealtime

    fun setTime(time: Long) {
        currentTime = time
    }

    fun setRealtime(realtime: Long) {
        currentRealtime = realtime
    }

    fun advance(millis: Long) {
        currentTime += millis
        currentRealtime += millis
    }
}


