package host.serv.djbigmuh.minutewatcher.core.common.util

class FixedTimeProvider(
    private var currentTime: Long = 0L,
    private var currentRealtime: Long = 0L,
    private var currentUptime: Long = 0L,
) : TimeProvider {
    override fun now(): Long = currentTime

    override fun elapsedRealtime(): Long = currentRealtime

    override fun uptimeMillis(): Long = currentUptime

    fun setTime(time: Long) {
        currentTime = time
    }

    fun setRealtime(realtime: Long) {
        currentRealtime = realtime
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
