package com.example.minutewatcher.core.data

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface StatsResult {
    data class Success(
        val bytes: Long,
    ) : StatsResult

    data class Error(
        val exception: Exception,
    ) : StatsResult

    data object Unavailable : StatsResult
}

/**
 * Interface for querying network statistics.
 */
interface NetworkStatsProvider {
    /**
     * Queries the total mobile bytes (RX + TX) for the given time range.
     * Throws SecurityException if permissions are missing.
     */
    fun queryMobileBytes(
        startTime: Long,
        endTime: Long,
    ): StatsResult
}

/**
 * Parses raw rxBytes and txBytes into a [StatsResult].
 * Returns [StatsResult.Unavailable] if either value is negative (VALUE_UNKNOWN).
 */
@VisibleForTesting
internal fun parseNetworkStatsBytes(
    rxBytes: Long,
    txBytes: Long,
): StatsResult {
    if (rxBytes < 0 || txBytes < 0) {
        AppLogger.w("Invalid network stats bucket bytes: rxBytes=$rxBytes, txBytes=$txBytes")
        return StatsResult.Unavailable
    }
    return StatsResult.Success(rxBytes + txBytes)
}

@Singleton
class RealNetworkStatsProvider
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : NetworkStatsProvider {
        private val networkStatsManager =
            context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

        override fun queryMobileBytes(
            startTime: Long,
            endTime: Long,
        ): StatsResult {
            val manager = networkStatsManager ?: return StatsResult.Unavailable

            return try {
                val bucket =
                    manager.querySummaryForDevice(
                        NetworkCapabilities.TRANSPORT_CELLULAR,
                        null,
                        startTime,
                        endTime,
                    )
                parseNetworkStatsBytes(bucket.rxBytes, bucket.txBytes)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Error querying NetworkStatsManager", e)
                StatsResult.Error(e)
            }
        }
    }
