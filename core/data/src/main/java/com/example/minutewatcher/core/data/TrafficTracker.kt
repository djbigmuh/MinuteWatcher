package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks mobile traffic usage for the device.
 * Attributes all mobile traffic to the CURRENT DEFAULT DATA SIM.
 */
@Singleton
class TrafficTracker
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simUsageResetRepository: SimUsageResetRepository,
        private val callSessionRepository: CallSessionRepository,
        private val permissionManager: PermissionManager,
        private val timeProvider: TimeProvider,
        private val networkStatsProvider: NetworkStatsProvider,
    ) : TrackingStatusProvider {
        override val key: String = "traffic"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        override suspend fun recover() {
            updateTrafficUsage()
        }

        private val simChangeMutex = Mutex()

        private val trafficUpdateMutex = Mutex()

        private val inMemoryLastDataSubId = AtomicInteger(-1)

        private val baselineNeedsRetry = ConcurrentHashMap<String, Int>()

        private val targetResolver = TrafficTargetResolver(permissionManager)

        /**
         * Resets the traffic baseline for a specific identity.
         * @throws java.io.IOException if DataStore write fails
         * @throws SecurityException if permission is revoked during operation
         */
        suspend fun resetBaseline(identity: String) {
            trafficUpdateMutex.withLock {
                simChangeMutex.withLock {
                    try {
                        if (!checkPermissions()) return@withLock

                        val usage = simUsageRepository.getSimUsage(identity).firstWithTimeout() ?: return@withLock
                        val statsResult = getCurrentMobileBytes(usage.lastResetDate)
                        if (statsResult is StatsResult.Success) {
                            val currentBytes = statsResult.bytes
                            simUsageResetRepository.setLastTotalBytes(
                                identity,
                                currentBytes,
                            )
                            AppLogger.d("Traffic baseline reset for $identity to $currentBytes")

                            syncLastDataSubIdIfNeeded(identity)

                            baselineNeedsRetry.remove(identity)

                            _status.value = TrackingStatus.Active
                        } else {
                            val message =
                                if (statsResult is StatsResult.Error) {
                                    AppLogger.e("Traffic stats baseline query failed", statsResult.exception)
                                    statsResult.exception.message
                                } else {
                                    "Stats unavailable"
                                }
                            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
                        }
                    } catch (e: SecurityException) {
                        AppLogger.e("SecurityException in TrafficTracker", e)
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Error in resetBaseline", e)
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    }
                }
            }
        }

        /**
         * Checks and updates traffic usage for the current active SIM.
         * @throws java.io.IOException if DataStore write fails
         * @throws SecurityException if permission is revoked during operation
         */
        @Suppress("ReturnCount")
        suspend fun updateTrafficUsage() {
            trafficUpdateMutex.withLock {
                try {
                    if (!checkPermissions()) return

                    val activeSims = getActiveSimsAndCleanup()
                    if (activeSims == null) {
                        AppLogger.w("TrafficTracker: observeActiveSims timed out, skipping update")
                        _status.value =
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.UNKNOWN,
                                "Identity read timeout in TrafficTracker",
                            )
                        return
                    }

                    val target = targetResolver.resolve(activeSims)
                    if (target == null) {
                        AppLogger.d("TrafficTracker: no active SIM found, setting Idle")
                        _status.value = TrackingStatus.Idle
                        return
                    }

                    simChangeMutex.withLock {
                        ensureInMemoryLastDataSubId()
                        val savedLastDataSubId = inMemoryLastDataSubId.get()
                        val isSimChanged =
                            (savedLastDataSubId != -1) &&
                                (target.dataSubId != -1) &&
                                (savedLastDataSubId != target.dataSubId)

                        if (isSimChanged) {
                            val targetIdentity = simIdentityRepository.getIdentity(target.slot)
                            val retryCount = baselineNeedsRetry.getOrDefault(targetIdentity, 0)
                            handleSimChange(target.slot, target.dataSubId, savedLastDataSubId, retryCount)
                            handleSkipDelta(target, retryCount)
                        } else {
                            val targetIdentity = simIdentityRepository.getIdentity(target.slot)
                            baselineNeedsRetry.keys.removeIf { it != targetIdentity }
                            performTrafficUpdate(target, targetIdentity)
                        }
                    }
                } catch (e: SecurityException) {
                    AppLogger.e("SecurityException in TrafficTracker", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error updating traffic", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                }
            }
        }

        private suspend fun getActiveSimsAndCleanup(): List<ActiveSimInfo>? {
            val activeSims = simIdentityRepository.observeActiveSims().firstWithTimeout() ?: return null
            val activeIdentities = activeSims.asSequence().map { it.identity.stableId }.toSet()
            baselineNeedsRetry.keys.removeIf { it !in activeIdentities }
            return activeSims
        }

        private fun handleSkipDelta(
            target: TargetInfo,
            retryCount: Int,
        ) {
            AppLogger.d(
                "Traffic delta skipped due to SIM change " +
                    "(slot=${target.slot}, retryCount=$retryCount)",
            )
            _status.value =
                if (target.isFallback) {
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                        slotIndex = target.slot,
                    )
                } else {
                    TrackingStatus.Active
                }
        }

        private suspend fun performTrafficUpdate(
            target: TargetInfo,
            targetIdentity: String,
        ) {
            val usage =
                simUsageRepository
                    .getSimUsage(targetIdentity)
                    .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)

            if (usage == null) {
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Timeout reading simUsage",
                    )
                return
            }

            val statsResult = getCurrentMobileBytes(usage.lastResetDate)
            if (handleBaselineRetry(target, targetIdentity, statsResult)) return

            processStatsResult(target, targetIdentity, statsResult)
        }

        private suspend fun processStatsResult(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
        ) {
            val lastTotalBytes =
                if (statsResult is StatsResult.Success) {
                    simUsageResetRepository
                        .getLastTotalBytes(targetIdentity)
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                } else {
                    -1L
                }

            if (lastTotalBytes == null) {
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Timeout reading lastTotalBytes",
                    )
            } else {
                executeTrafficUpdate(target, targetIdentity, statsResult, lastTotalBytes)
            }
        }

        private fun checkPermissions(): Boolean {
            val error =
                when {
                    !permissionManager.isTelephonySupported() -> {
                        AppLogger.w("TrafficTracker: telephony not supported")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "SubscriptionManager unavailable",
                        )
                    }

                    !permissionManager.hasUsageStatsPermission() -> {
                        AppLogger.w("TrafficTracker: usage stats permission missing")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                            "Usage Access missing",
                        )
                    }

                    !permissionManager.hasPhoneStatePermission() -> {
                        AppLogger.w("TrafficTracker: phone state permission missing")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                            "Phone State missing",
                        )
                    }

                    else -> null
                }

            error?.let { _status.value = it }
            return error == null
        }

        private suspend fun executeTrafficUpdate(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
            lastTotalBytes: Long,
        ) {
            if (statsResult !is StatsResult.Success) {
                val message =
                    if (statsResult is StatsResult.Error) {
                        AppLogger.e("Traffic stats query failed", statsResult.exception)
                        statsResult.exception.message
                    } else {
                        "Stats unavailable"
                    }
                AppLogger.w("Traffic stats unavailable for $targetIdentity: $message")
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
                return
            }

            updateTrafficBaselineAndUsage(targetIdentity, statsResult.bytes, lastTotalBytes, target)
            updateLastDataSubId(target.dataSubId)
            _status.value =
                if (target.isFallback) {
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                        slotIndex = target.slot,
                    )
                } else {
                    TrackingStatus.Active
                }
        }

        private suspend fun updateTrafficBaselineAndUsage(
            targetIdentity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
            target: TargetInfo,
        ) {
            if ((lastTotalBytes == -1L) || (currentBytes < lastTotalBytes)) {
                simUsageResetRepository.setLastTotalBytes(targetIdentity, currentBytes)
                AppLogger.d("Traffic baseline initialized/reset for $targetIdentity to $currentBytes")
            } else {
                handleTrafficDelta(targetIdentity, currentBytes, lastTotalBytes, target.slot)
            }
        }

        private suspend fun handleBaselineRetry(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
        ): Boolean {
            var handled = false
            if (statsResult is StatsResult.Success) {
                val currentBytes = statsResult.bytes
                if (baselineNeedsRetry.remove(targetIdentity) != null) {
                    simUsageResetRepository.setLastTotalBytes(
                        targetIdentity,
                        currentBytes,
                    )
                    AppLogger.d("Traffic baseline reset on retry for $targetIdentity to $currentBytes")

                    updateLastDataSubId(target.dataSubId)

                    _status.value =
                        if (target.isFallback) {
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                                slotIndex = target.slot,
                            )
                        } else {
                            TrackingStatus.Active
                        }
                    handled = true
                }
            } else if (baselineNeedsRetry.getOrDefault(targetIdentity, 0) > 0) {
                AppLogger.w("Stats still unavailable for $targetIdentity, will retry baseline reset later")
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Stats unavailable, retry pending",
                    )
                handled = true
            }
            return handled
        }

        private suspend fun handleTrafficDelta(
            identity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
            slot: Int,
        ) {
            val deltaBytes = currentBytes - lastTotalBytes
            if (deltaBytes > 0L) {
                val deltaMB = (deltaBytes.toDouble() / Constants.BYTES_PER_MB).toFloat()
                if (deltaMB >= Constants.Traffic.MIN_DELTA_MB) {
                    simUsageResetRepository.setLastTotalBytes(
                        identity,
                        currentBytes,
                    )
                    simUsageRepository.incrementTrafficUsed(identity, deltaMB)
                    AppLogger.d("Added $deltaMB MB to identity $identity (SIM $slot)")
                }
            }
        }

        private suspend fun updateLastDataSubId(dataSubId: Int) {
            if (dataSubId != -1) {
                callSessionRepository.setLastDataSubId(dataSubId)
                inMemoryLastDataSubId.set(dataSubId)
            }
        }

        private fun getCurrentMobileBytes(startTime: Long): StatsResult {
            val now = timeProvider.now()
            val lookbackStart = now - (Constants.Traffic.LOOKBACK_DAYS * Constants.MILLIS_PER_DAY)

            val effectiveStart =
                when {
                    startTime == 0L -> {
                        AppLogger.d("First run: using 30-day lookback window")
                        lookbackStart
                    }
                    startTime < lookbackStart -> {
                        AppLogger.w(
                            "Billing period start is older than 30 days, clamping to lookback window. " +
                                "Some traffic data may be missed.",
                        )
                        lookbackStart
                    }
                    else -> startTime
                }

            return networkStatsProvider
                .queryMobileBytes(
                    effectiveStart,
                    now,
                )
        }

        private suspend fun syncLastDataSubIdIfNeeded(identity: String) {
            // Sync in-memory last data subId if this identity is currently the default data SIM.
            // This prevents handleSimChange from triggering immediately after a manual reset.
            val currentDataSubId =
                try {
                    permissionManager.getDefaultDataSubscriptionId()
                } catch (_: Exception) {
                    -1
                }
            if (currentDataSubId != -1) {
                val info =
                    permissionManager.getActiveSubscriptions().find {
                        it.subscriptionId ==
                            currentDataSubId
                    }
                if (info != null) {
                    val currentIdentity = simIdentityRepository.getIdentity(info.simSlotIndex)
                    if (currentIdentity == identity) {
                        updateLastDataSubId(currentDataSubId)
                    }
                }
            }
        }

        private suspend fun ensureInMemoryLastDataSubId() {
            if (inMemoryLastDataSubId.get() != -1) return

            val savedId = callSessionRepository.getLastDataSubId().firstWithTimeout() ?: -1
            inMemoryLastDataSubId.compareAndSet(-1, savedId)
        }

        private suspend fun resetStatsAfterSimChange(
            identity: String,
            statsResult: StatsResult,
        ) {
            if (statsResult is StatsResult.Success) {
                val currentBytes = statsResult.bytes
                simUsageResetRepository.setLastTotalBytes(
                    identity,
                    currentBytes,
                )
                AppLogger.d("Baseline reset successfully for $identity")
            }
        }

        /**
         * Handles default data SIM change.
         *
         * Scenarios:
         * 1. Success: Current mobile stats are available. We reset the baseline to the current value
         *    and update [inMemoryLastDataSubId]. Future updates will track delta from this point.
         * 2. Retry: Stats are temporarily unavailable (e.g., system busy). We record the attempt
         *    in [baselineNeedsRetry]. The next [updateTrafficUsage] call will retry the reset.
         * 3. Exhaustion: After [MAX_BASELINE_RETRIES], we give up on fetching previous stats.
         *    We force [SimUsageResetRepository.setLastTotalBytes] to -1, which triggers a fresh
         *    baseline initialization on the next successful update.
         */
        private suspend fun handleSimChange(
            targetSim: Int,
            currentDataSubId: Int,
            savedLastDataSubId: Int,
            retryCount: Int,
        ) {
            AppLogger.d(
                "Default data SIM changed from $savedLastDataSubId to $currentDataSubId. " +
                    "Resetting baseline.",
            )
            try {
                val identity = simIdentityRepository.getIdentity(targetSim)

                // Ensure we only track retry for the current target.
                baselineNeedsRetry.keys.removeIf { it != identity }

                val shouldReturn =
                    handleSimChangeFailure(
                        identity,
                        StatsResult.Unavailable,
                        retryCount,
                        isLimitCheckOnly = true,
                    )
                if (shouldReturn) {
                    updateLastDataSubId(currentDataSubId)
                    return
                }

                val usage = simUsageRepository.getSimUsage(identity).firstWithTimeout()
                val statsResult = fetchStatsOnSimChange(usage)

                if (statsResult is StatsResult.Success) {
                    resetStatsAfterSimChange(identity, statsResult)
                    baselineNeedsRetry.remove(identity)
                    // Update subId ONLY after successful baseline reset to ensure no delta is lost
                    updateLastDataSubId(currentDataSubId)
                } else {
                    handleSimChangeFailure(identity, statsResult, retryCount)
                    updateLastDataSubId(currentDataSubId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to handle SIM change", e)
            }
        }

        private suspend fun handleSimChangeFailure(
            identity: String,
            statsResult: StatsResult,
            retryCount: Int,
            isLimitCheckOnly: Boolean = false,
        ): Boolean =
            when {
                retryCount >= MAX_BASELINE_RETRIES -> {
                    AppLogger.w(
                        "Baseline reset retries exhausted for $identity after $retryCount attempts. " +
                            "Forcing baseline re-initialization.",
                    )
                    simUsageResetRepository.setLastTotalBytes(identity, -1L)
                    baselineNeedsRetry.remove(identity)
                    true
                }

                isLimitCheckOnly -> false

                else -> {
                    val reason =
                        if (statsResult is StatsResult.Error) {
                            AppLogger.e("Traffic stats query failed on SIM change", statsResult.exception)
                            statsResult.exception.message
                        } else {
                            "Stats unavailable"
                        }
                    AppLogger.w(
                        "Stats unavailable on SIM change for $identity: $reason. " +
                            "Baseline reset will be retried (attempt ${retryCount + 1}).",
                    )
                    simUsageResetRepository.setLastTotalBytes(identity, -1L)
                    baselineNeedsRetry.merge(identity, 1, Int::plus)
                    true
                }
            }

        private fun fetchStatsOnSimChange(usage: com.example.minutewatcher.core.model.SimUsage?): StatsResult =
            if (usage != null) {
                try {
                    getCurrentMobileBytes(usage.lastResetDate)
                } catch (e: Exception) {
                    StatsResult.Error(e)
                }
            } else {
                StatsResult.Unavailable
            }

        companion object {
            private const val MAX_BASELINE_RETRIES = 5
        }
    }

internal data class TargetInfo(
    val slot: Int,
    val dataSubId: Int,
    val isFallback: Boolean = false,
)

/**
 * Вспомогательный класс для определения целевой SIM-карты для отслеживания трафика.
 * Вынесен из [TrafficTracker] для упрощения основного класса.
 */
internal class TrafficTargetResolver(
    private val permissionManager: PermissionManager,
) {
    fun resolve(activeSims: List<ActiveSimInfo>): TargetInfo? {
        val (targetSim, currentDataSubId, isFallback) = resolveTargetSim(activeSims) ?: return null

        return TargetInfo(
            targetSim,
            currentDataSubId,
            isFallback = isFallback,
        )
    }

    private fun resolveTargetSim(activeSims: List<ActiveSimInfo>): Triple<Int, Int, Boolean>? {
        var targetSim = -1
        var currentDataSubId = -1
        var isFallback = false
        try {
            currentDataSubId = permissionManager.getDefaultDataSubscriptionId()
            if (currentDataSubId != -1) {
                val subInfoList = permissionManager.getActiveSubscriptions()
                targetSim = subInfoList.find { it.subscriptionId == currentDataSubId }?.simSlotIndex ?: -1
            }
        } catch (e: Exception) {
            AppLogger.e("Error getting default data SIM", e)
        }

        if (targetSim == -1) {
            val resolveResult = resolveTargetSimFromActiveSims(activeSims)
            if (resolveResult != null) {
                targetSim = resolveResult.first
                currentDataSubId = resolveResult.second
                isFallback = resolveResult.third
            }
        }

        return if (targetSim != -1) Triple(targetSim, currentDataSubId, isFallback) else null
    }

    private fun resolveTargetSimFromActiveSims(activeSims: List<ActiveSimInfo>): Triple<Int, Int, Boolean>? {
        val defaultDataSim = activeSims.find { it.isDefaultData }

        return if (defaultDataSim != null) {
            Triple(defaultDataSim.slotIndex, defaultDataSim.subscriptionId, false)
        } else {
            val activeSubs = permissionManager.getActiveSubscriptions()
            activeSubs.minByOrNull { it.simSlotIndex }?.let { selected ->
                AppLogger.w(
                    "No default data SIM identified for traffic tracking. " +
                        "Using fallback heuristic: attributing all traffic to Slot ${selected.simSlotIndex}.",
                )
                Triple(selected.simSlotIndex, selected.subscriptionId, true)
            }
        }
    }
}
