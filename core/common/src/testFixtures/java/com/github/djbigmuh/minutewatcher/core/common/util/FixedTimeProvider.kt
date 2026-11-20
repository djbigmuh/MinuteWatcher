package com.github.djbigmuh.minutewatcher.core.common.util

import java.time.ZoneId

class FixedTimeProvider(
    private var currentTime: Long = 0L,
    private var currentRealtime: Long = 0L,
    private var currentUptime: Long = 0L,
    private var zone: ZoneId = ZoneId.of("UTC"),
) : TimeProvider {
    override fun now(): Long = currentTime

    override fun elapsedRealtime(): Long = currentRealtime

    override fun uptimeMillis(): Long = currentUptime

    override fun zoneId(): ZoneId = zone

    fun setTime(time: Long) {
        currentTime = time
    }

    fun setRealtime(realtime: Long) {
        currentRealtime = realtime
    }

    fun setZone(zoneId: ZoneId) {
        zone = zoneId
    }

    fun advance(millis: Long) {
        currentTime += millis
        currentRealtime += millis
        currentUptime += millis
    }

    fun advanceRealtimeOnly(millis: Long) {
        currentRealtime += millis
    }
}
