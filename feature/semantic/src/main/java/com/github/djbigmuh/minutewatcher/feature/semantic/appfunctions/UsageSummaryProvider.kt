package com.github.djbigmuh.minutewatcher.feature.semantic.appfunctions

import androidx.appfunctions.AppFunctionElementNotFoundException
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.PackageType
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageSummaryProvider
    @Inject
    constructor(
        private val usageStateRepository: UsageStateRepository,
    ) {
        suspend fun getUsageSummary(): UsageSummary {
            val snapshot = fetchAndValidateSnapshot()
            return mapSnapshot(snapshot)
        }

        private suspend fun fetchAndValidateSnapshot(): GlobalSnapshot {
            var snapshot = usageStateRepository.observeSnapshot().value
            if (snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    usageStateRepository.refresh()
                    snapshot = usageStateRepository.observeSnapshot().value
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for AppFunction provider stability.
                    AppLogger.w("UsageSummaryProvider: refresh failed during getUsageSummary", e)
                }
            }

            validateSnapshot(snapshot)
            return snapshot
        }

        private fun validateSnapshot(snapshot: GlobalSnapshot) {
            val exception =
                when {
                    snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP ->
                        AppFunctionElementNotFoundException("Usage data is not available yet")

                    snapshot.simSnapshots.isEmpty() ->
                        AppFunctionElementNotFoundException("No active SIM cards")

                    else -> null
                }
            if (exception != null) {
                throw exception
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
