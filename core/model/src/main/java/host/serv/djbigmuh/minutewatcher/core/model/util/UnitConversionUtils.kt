package host.serv.djbigmuh.minutewatcher.core.model.util

import host.serv.djbigmuh.minutewatcher.core.model.Constants
import kotlin.math.roundToLong

/**
 * Single authoritative utility for unit conversions and rounding rules.
 * Enforces binary prefixes for data units (1 MB = 1024 * 1024 bytes, 1 GB = 1024 MB)
 * and ceiling rounding for call duration seconds to minutes.
 */
object UnitConversionUtils {
    private const val TRAFFIC_ROUNDING_FACTOR = 100.0

    /**
     * Converts bytes to MB using binary prefix (1024 * 1024 bytes per MB).
     */
    fun bytesToMb(bytes: Long): Float {
        if (bytes <= 0L) return 0f
        return (bytes.toDouble() / Constants.BYTES_PER_MB).toFloat()
    }

    /**
     * Converts MB to bytes using binary prefix (1024 * 1024 bytes per MB).
     */
    fun mbToBytes(mb: Float): Long {
        if (mb <= 0f) return 0L
        return (mb.toDouble() * Constants.BYTES_PER_MB).toLong()
    }

    /**
     * Converts bytes to GB using binary prefix (1024 * 1024 * 1024 bytes per GB).
     */
    fun bytesToGb(bytes: Long): Float {
        if (bytes <= 0L) return 0f
        return (bytes.toDouble() / Constants.BYTES_PER_GB).toFloat()
    }

    /**
     * Converts GB to bytes using binary prefix (1024 * 1024 * 1024 bytes per GB).
     */
    fun gbToBytes(gb: Float): Long {
        if (gb <= 0f) return 0L
        return (gb.toDouble() * Constants.BYTES_PER_GB).toLong()
    }

    /**
     * Converts MB to GB using binary prefix (1024 MB per GB).
     */
    fun mbToGb(mb: Float): Float {
        if (mb <= 0f) return 0f
        return (mb.toDouble() / Constants.MB_PER_GB.toDouble()).toFloat()
    }

    /**
     * Converts GB to MB using binary prefix (1024 MB per GB).
     */
    fun gbToMb(gb: Float): Float {
        if (gb <= 0f) return 0f
        return (gb.toDouble() * Constants.MB_PER_GB.toDouble()).toFloat()
    }

    /**
     * Rounds traffic in MB to 2 decimal places.
     */
    fun roundTrafficMb(mb: Float): Float {
        if (mb <= 0f) return 0f
        val newValue = mb.toDouble()
        val rounded = (newValue * TRAFFIC_ROUNDING_FACTOR).roundToLong() / TRAFFIC_ROUNDING_FACTOR
        return rounded.toFloat()
    }

    /**
     * Converts call duration in seconds to whole minutes using ceiling rounding.
     * Non-positive seconds (seconds <= 0) return 0.
     */
    fun secondsToMinutesCeil(seconds: Int): Int {
        if (seconds <= 0) return 0
        return ((seconds + Constants.Time.SECONDS_IN_MINUTE) - 1) / Constants.Time.SECONDS_IN_MINUTE
    }
}
