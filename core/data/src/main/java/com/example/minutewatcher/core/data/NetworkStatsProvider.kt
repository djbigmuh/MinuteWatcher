package com.example.minutewatcher.core.data

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import com.example.minutewatcher.core.common.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for querying network statistics.
 */
interface NetworkStatsProvider {
    /**
     * Queries the total mobile bytes (RX + TX) for the given time range.
     * Throws SecurityException if permissions are missing.
     * Returns -1 if an error occurs during the query.
     */
    fun queryMobileBytes(
        startTime: Long,
        endTime: Long,
    ): Long
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
        ): Long {
            if (networkStatsManager == null) return -1L

            return try {
                val bucket =
                    networkStatsManager.querySummaryForDevice(
                        NetworkCapabilities.TRANSPORT_CELLULAR,
                        null,
                        startTime,
                        endTime,
                    )
                bucket.rxBytes + bucket.txBytes
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Error querying NetworkStatsManager", e)
                -1L
            }
        }
    }
