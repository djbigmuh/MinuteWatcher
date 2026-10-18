package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.firstWithTimeout
import host.serv.djbigmuh.minutewatcher.core.common.util.firstWithTimeoutOrLog
import host.serv.djbigmuh.minutewatcher.core.domain.AutoResetUseCase
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Manages automatic resets of SIM usage stats based on billing cycles.
 *
 * Implementation of [AutoResetUseCase].
 * Safe to call from any coroutine context.
 */
@Singleton
class ResetManager
@Inject
constructor(
    private val simIdentityRepository: SimIdentityRepository,
    private val simUsageRepository: SimUsageRepository,
    private val simUsageResetRepository: SimUsageResetRepository,
    private val trafficTracker: TrafficTracker,
    private val dataChangeNotifier: DataChangeNotifier,
    private val timeProvider: TimeProvider,
) : AutoResetUseCase {
    private val lastResetCheck = ConcurrentHashMap<String, Long>()
    private val pendingResets = ConcurrentHashMap.newKeySet<String>()

    /**
     * Checks and performs automatic resets for all active SIMs.
     *
     * @param shouldNotify True if a data change notification should be broadcasted after reset.
     * Safe to call from any coroutine context.
     * @return `true` if all SIMs were successfully processed or skipped due to cooldown.
     * `false` if at least one SIM failed due to IO error or usage data timeout.
     * @throws CancellationException if the reset process is cancelled.
     */
    @Suppress("CyclomaticComplexMethod")
    override suspend fun performAutoReset(shouldNotify: Boolean): Boolean {
        val now = timeProvider.now()
        val nowElapsed = timeProvider.elapsedRealtime()

        val activeSims =
            simIdentityRepository.observeActiveSims().firstWithTimeout()
                ?: run {
                    AppLogger.e("ResetManager: Timeout observing active SIMs during auto-reset check")
                    return false
                }

        if (activeSims.isEmpty()) {
            AppLogger.w("ResetManager: No active SIMs detected during auto-reset check.")
        }

        val resetIdentities = mutableSetOf<String>()
        var ioException: IOException? = null
        var successCount = 0
        var skippedCount = 0

        val allProcessed =
            withTimeoutOrNull(RESET_TOTAL_TIMEOUT_MS.milliseconds) {
                for (simInfo in activeSims) {
                    try {
                        if (processSingleSimReset(simInfo, now, nowElapsed, resetIdentities)) {
                            successCount++
                        } else {
                            skippedCount++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        AppLogger.e(
                            "ResetManager: reset failed for SIM ${simInfo.identity.stableId}, continuing",
                            e,
                        )
                        ioException = e
                        skippedCount++
                    } catch (e: Exception) {
                        AppLogger.e("ResetManager: Unexpected error resetting SIM ${simInfo.identity.stableId}", e)
                        skippedCount++
                    }
                }
                true
            } ?: run {
                AppLogger.e("ResetManager: total reset timeout after ${RESET_TOTAL_TIMEOUT_MS}ms")
                false
            }

        cleanupStaleCheckTimestamps(activeSims)

        if (resetIdentities.isNotEmpty()) {
            resetIdentities.forEach { identity ->
                try {
                    trafficTracker.resetBaseline(identity)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("ResetManager: failed to reset traffic baseline for $identity", e)
                }
            }
            if (shouldNotify) dataChangeNotifier.notifyDataChanged()
        }

        return allProcessed && handleAutoResetResult(ioException, skippedCount, successCount, activeSims.size)
    }

    /**
     * Processes a single SIM reset with atomic single-flight gating and cooldown protection.
     *
     * **Reliability criteria:**
     * - Single-flight is ensured by atomic `pendingResets.add`.
     * - Cooldown is enforced via `lastResetCheck`.
     * - Cleanup is non-blocking to prevent stalling during cancellation.
     */
    private suspend fun processSingleSimReset(
        simInfo: ActiveSimInfo,
        now: Long,
        nowElapsed: Long,
        resetIdentities: MutableSet<String>,
    ): Boolean {
        val identity = simInfo.identity.stableId

        val lastCheck = lastResetCheck[identity]
        val isCoolingDown = (lastCheck != null) && ((nowElapsed - lastCheck) < CHECK_INTERVAL_MS)
        if (isCoolingDown || !pendingResets.add(identity)) {
            return true // Skip or already pending is not a failure of the coordinator
        }

        val result: Boolean
        try {
            result = checkAndPerformSingleReset(simInfo, now, resetIdentities)
        } finally {
            lastResetCheck[identity] = nowElapsed
            pendingResets.remove(identity)
        }
        return result
    }

    private suspend fun checkAndPerformSingleReset(
        simInfo: ActiveSimInfo,
        now: Long,
        resetIdentities: MutableSet<String>,
    ): Boolean {
        val identity = simInfo.identity.stableId
        val usage =
            simUsageRepository
                .getSimUsage(identity)
                .firstWithTimeoutOrLog(
                    timeoutMillis = Constants.Flow.SHORT_TIMEOUT_MS,
                    tag = "ResetManager",
                    key = "simUsage_$identity",
                )

        if (usage == null) {
            AppLogger.w("ResetManager: Timeout reading usage for SIM $identity, skipping reset check.")
            return false
        }

        val resetPerformed = checkAndPerformResetForSimInternal(simInfo, now, usage)
        if (resetPerformed) {
            resetIdentities.add(identity)
        }
        return true
    }

    private fun cleanupStaleCheckTimestamps(activeSims: List<ActiveSimInfo>) {
        val activeIdentitySet = activeSims.asSequence().map { it.identity.stableId }.toSet()
        lastResetCheck.keys.removeAll { it !in activeIdentitySet }
    }

    private fun handleAutoResetResult(
        ioException: IOException?,
        skippedCount: Int,
        successCount: Int,
        totalActiveSims: Int,
    ): Boolean {
        if (ioException != null || skippedCount > 0) {
            AppLogger.w(
                "ResetManager: auto-reset partially failed " +
                    "($successCount/$totalActiveSims succeeded, $skippedCount skipped, io=${ioException != null})",
            )
            return false
        }
        return true
    }

    private suspend fun checkAndPerformResetForSimInternal(
        simInfo: ActiveSimInfo,
        now: Long,
        usage: SimUsage,
    ): Boolean {
        val identity = simInfo.identity.stableId
        var result = false
        if (usage.autoReset) {
            val reset =
                simUsageResetRepository.resetSimUsageIfDue(
                    identity,
                    usage.autoResetDay,
                    now,
                )
            if (reset) {
                AppLogger.d(
                    "Auto reset performed for SIM ${simInfo.slotIndex} ($identity), day: ${usage.autoResetDay}",
                )
                result = true
            }
        }
        return result
    }

    companion object {
        private val CHECK_INTERVAL_MS = 45.minutes.inWholeMilliseconds
        private const val RESET_TOTAL_TIMEOUT_MS = 30_000L
    }
}
