package host.serv.djbigmuh.minutewatcher.core.common.util

import android.os.SystemClock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

interface TimeProvider {
    fun now(): Long

    fun elapsedRealtime(): Long

    fun uptimeMillis(): Long

    fun zoneId(): ZoneId = ZoneId.systemDefault()
}

@Singleton
class SystemTimeProvider
    @Inject
    constructor() : TimeProvider {
        override fun now(): Long = System.currentTimeMillis()

        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

        override fun uptimeMillis(): Long = SystemClock.uptimeMillis()

    override fun zoneId(): ZoneId = ZoneId.systemDefault()
    }
