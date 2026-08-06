package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

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
        @ApplicationScope private val scope: CoroutineScope,
    ) : TrackingStatusProvider {
        override val key: String = "traffic"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        override fun recover() {
            scope.launch {
                updateTrafficUsage()
            }
        }

        private val trafficMutex = Mutex()

        private val inMemoryLastDataSubId = AtomicInteger(-1)
        private val baselineNeedsRetry = java.util.concurrent.ConcurrentHashMap<String, Int>()

        private data class TargetInfo(
            val slot: Int,
            val dataSubId: Int,
            val skipDelta: Boolean,
            val isFallback: Boolean = false,
            val retryCount: Int = 0,
        )

        suspend fun resetBaseline(identity: String) {
            trafficMutex.withLock {
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
                                statsResult.exception.message ?: "Stats error"
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

        suspend fun updateTrafficUsage() {
            trafficMutex.withLock {
                try {
                    if (!checkPermissions()) return@withLock

                    val target =
                        getTargetSimInfo() ?: run {
                            AppLogger.d("TrafficTracker: no active SIM detected for traffic tracking")
                            _status.value = TrackingStatus.Idle
                            return@withLock
                        }

                    if (target.skipDelta) {
                        _status.value =
                            if (target.isFallback) {
                                TrackingStatus.Error(
                                    TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                                    target.slot.toString(),
                                )
                            } else {
                                TrackingStatus.Active
                            }
                        return@withLock
                    }

                    processTrafficUpdate(target)
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

            if (error != null) {
                _status.value = error
            }

            return error == null
        }

        private suspend fun processTrafficUpdate(target: TargetInfo) {
            val targetIdentity = simIdentityRepository.getIdentity(target.slot)

            val usage =
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    simUsageRepository.getSimUsage(targetIdentity).first()
                }

            if (usage == null) {
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Timeout reading simUsage",
                    )
            } else {
                val statsResult = getCurrentMobileBytes(usage.lastResetDate)
                val isHandled = handleBaselineRetry(target, targetIdentity, statsResult)

                if (!isHandled) {
                    executeTrafficUpdate(target, targetIdentity, statsResult)
                }
            }
        }

        private suspend fun executeTrafficUpdate(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
        ) {
            if (statsResult !is StatsResult.Success) {
                val message =
                    if (statsResult is StatsResult.Error) {
                        statsResult.exception.message ?: "Stats error"
                    } else {
                        "Stats unavailable"
                    }
                AppLogger.w("Traffic stats unavailable for $targetIdentity: $message")
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
                return
            }

            val lastTotalBytes =
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    simUsageResetRepository.getLastTotalBytes(targetIdentity).first()
                }

            if (lastTotalBytes == null) {
                _status.value =
                    TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Timeout reading lastTotalBytes")
            } else {
                updateTrafficBaselineAndUsage(targetIdentity, statsResult.bytes, lastTotalBytes, target)
                updateLastDataSubId(target.dataSubId)
                _status.value =
                    if (target.isFallback) {
                        TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION, target.slot.toString())
                    } else {
                        TrackingStatus.Active
                    }
            }
        }

        private suspend fun updateTrafficBaselineAndUsage(
            targetIdentity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
            target: TargetInfo,
        ) {
            if (lastTotalBytes == -1L || (currentBytes < lastTotalBytes)) {
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
                                target.slot.toString(),
                            )
                        } else {
                            TrackingStatus.Active
                        }
                    handled = true
                }
            } else if (baselineNeedsRetry.containsKey(targetIdentity)) {
                AppLogger.w("Stats still unavailable for $targetIdentity, will retry baseline reset later")
                _status.value = TrackingStatus.Idle
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

            return networkStatsProvider.queryMobileBytes(effectiveStart, now)
        }

        private suspend fun getTargetSimInfo(): TargetInfo? {
            val activeSims = simIdentityRepository.observeActiveSims().firstWithTimeout() ?: emptyList()
            val activeIdentitySet = activeSims.map { it.identity.stableId }.toSet()
            baselineNeedsRetry.keys.removeAll { it !in activeIdentitySet }

            val (targetSim, currentDataSubId, isFallback) = resolveTargetSim(activeSims) ?: return null

            ensureInMemoryLastDataSubId()

            val savedLastDataSubId = inMemoryLastDataSubId.get()
            val isSimChanged =
                savedLastDataSubId != -1 && currentDataSubId != -1 && savedLastDataSubId != currentDataSubId

            val identity = simIdentityRepository.getIdentity(targetSim)
            val retryCount = baselineNeedsRetry[identity] ?: 0

            if (isSimChanged) {
                handleSimChange(targetSim, currentDataSubId, savedLastDataSubId, retryCount)
                if (isFallback) {
                    AppLogger.w("SIM change detected with fallback attribution to slot $targetSim")
                }
            }

            return TargetInfo(
                targetSim,
                currentDataSubId,
                skipDelta = isSimChanged,
                isFallback = isFallback,
                retryCount = retryCount,
            )
        }

        private suspend fun resolveTargetSim(activeSims: List<ActiveSimInfo>): Triple<Int, Int, Boolean>? {
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

        private suspend fun resolveTargetSimFromActiveSims(
            activeSims: List<ActiveSimInfo>,
        ): Triple<Int, Int, Boolean>? {
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
            if (inMemoryLastDataSubId.get() == -1) {
                val savedId = callSessionRepository.getLastDataSubId().firstWithTimeout() ?: -1
                inMemoryLastDataSubId.compareAndSet(-1, savedId)
            }
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
                baselineNeedsRetry.keys.removeAll { it != identity }

                if (handleRetryLimitExceeded(identity, retryCount, currentDataSubId)) return

                val usage = simUsageRepository.getSimUsage(identity).firstWithTimeout()
                val statsResult = fetchStatsOnSimChange(usage)

                if (statsResult is StatsResult.Success) {
                    resetStatsAfterSimChange(identity, statsResult)
                    // Successfully reset baseline, now we can consume the SIM change
                    updateLastDataSubId(currentDataSubId)
                    baselineNeedsRetry.remove(identity)
                } else {
                    handleFailedSimChangeAttempt(identity, statsResult, retryCount)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to handle SIM change", e)
            }
        }

        private suspend fun handleRetryLimitExceeded(
            identity: String,
            retryCount: Int,
            currentDataSubId: Int,
        ): Boolean {
            if (retryCount >= MAX_BASELINE_RETRIES) {
                AppLogger.w(
                    "Giving up baseline reset for $identity after $retryCount retries. " +
                        "Forcing baseline re-initialization on next update.",
                )
                simUsageResetRepository.setLastTotalBytes(identity, -1L)
                baselineNeedsRetry.remove(identity)
                updateLastDataSubId(currentDataSubId)
                return true
            }
            return false
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

        private fun handleFailedSimChangeAttempt(
            identity: String,
            statsResult: StatsResult,
            retryCount: Int,
        ) {
            val reason =
                if (statsResult is StatsResult.Error) {
                    statsResult.exception.message ?: "Stats error"
                } else {
                    "Stats unavailable"
                }
            AppLogger.w(
                "Stats unavailable on SIM change for $identity: $reason. " +
                    "Baseline reset will be retried (attempt ${retryCount + 1}).",
            )
            baselineNeedsRetry[identity] = retryCount + 1
        }

        companion object {
            private const val MAX_BASELINE_RETRIES = 5
        }
    }
