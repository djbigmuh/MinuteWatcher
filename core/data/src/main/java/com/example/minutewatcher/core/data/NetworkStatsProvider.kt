package com.example.minutewatcher.core.data

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import com.example.minutewatcher.core.common.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface StatsResult {
    data class Success(val bytes: Long) : StatsResult
    data class Error(val exception: Exception) : StatsResult
    object Unavailable : StatsResult
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

@Singleton
class RealNetworkStatsProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
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
                StatsResult.Success(bucket.rxBytes + bucket.txBytes)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Error querying NetworkStatsManager", e)
                StatsResult.Error(e)
            }
        }
    }
