package com.example.minutewatcher.feature.semantic.appfunctions

import androidx.appfunctions.AppFunctionElementNotFoundException
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageSummaryProvider
@Inject
constructor(
    private val usageStateRepository: UsageStateRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun getUsageSummary(): UsageSummary {
        var snapshot = usageStateRepository.observeSnapshot().value
        if (snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP) {
            try {
                usageStateRepository.refresh()
                snapshot = usageStateRepository.observeSnapshot().value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("UsageSummaryProvider: refresh failed during getUsageSummary", e)
            }
        }

        if (snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP) {
            throw AppFunctionElementNotFoundException("Usage data is not available yet")
        }
        if (snapshot.simSnapshots.isEmpty()) {
            throw AppFunctionElementNotFoundException("No active SIM cards")
        }

        return withContext(ioDispatcher) {
            mapSnapshot(snapshot)
        }
    }

    internal fun mapSnapshot(snapshot: GlobalSnapshot): UsageSummary {
        val sortedSims = snapshot.simSnapshots.sortedBy { it.slot }
        val simSummaries =
            sortedSims.map { sim ->
                val minutesEntity = sim.entities[PackageType.MINUTES]
                val dataEntity = sim.entities[PackageType.DATA]

                val minutesLimitSet = minutesEntity != null && minutesEntity.limit > 0
                val minutesRemaining = minutesEntity?.remaining ?: 0L
                val minutesLimit = minutesEntity?.limit ?: 0L

                val dataLimitSet = dataEntity != null && dataEntity.limit > 0
                val dataRemainingGb =
                    if (dataEntity != null) {
                        dataEntity.remaining.toDouble() / Constants.BYTES_PER_GB
                    } else {
                        0.0
                    }
                val dataLimitGb =
                    if (dataEntity != null) {
                        dataEntity.limit.toDouble() / Constants.BYTES_PER_GB
                    } else {
                        0.0
                    }

                val periodEndMillis = minutesEntity?.periodEnd ?: dataEntity?.periodEnd ?: 0L

                SimUsageSummary(
                    slot = sim.slot,
                    operatorName = sim.operatorName,
                    minutesLimitSet = minutesLimitSet,
                    minutesRemaining = minutesRemaining,
                    minutesLimit = minutesLimit,
                    dataLimitSet = dataLimitSet,
                    dataRemainingGb = dataRemainingGb,
                    dataLimitGb = dataLimitGb,
                    periodEndMillis = periodEndMillis,
                )
            }

        return UsageSummary(
            snapshotTimestampMillis = snapshot.timestamp,
            sims = simSummaries,
        )
    }
}
