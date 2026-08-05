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
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.BillingPeriodCalculator
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
        private val baselineNeedsRetry = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        private data class TargetInfo(
            val slot: Int,
            val dataSubId: Int,
            val skipDelta: Boolean,
            val isFallback: Boolean = false,
        )

        suspend fun resetBaseline(identity: String) {
            trafficMutex.withLock {
                try {
                    if (!checkPermissions()) return@withLock

                    val usage = simUsageRepository.getSimUsage(identity).firstWithTimeout() ?: return@withLock
                    val currentBytes = getCurrentMobileBytes(usage.lastResetDate, usage.autoResetDay)
                    if (currentBytes != -1L) {
                        simUsageResetRepository.setLastTotalBytes(
                            identity,
                            currentBytes,
                        )
                        AppLogger.d("Traffic baseline reset for $identity to $currentBytes")

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
                        baselineNeedsRetry.remove(identity)

                        _status.value = TrackingStatus.Active
                    } else {
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Stats unavailable")
                    }
                } catch (e: SecurityException) {
                    handleSecurityException(e)
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
                            handleNoTarget()
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
                    handleSecurityException(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error updating traffic", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                }
            }
        }

        @Suppress("ReturnCount")
        private fun checkPermissions(): Boolean {
            if (!permissionManager.isTelephonySupported()) {
                AppLogger.w("TrafficTracker: telephony not supported")
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "SubscriptionManager unavailable",
                    )
                return false
            }
            if (!permissionManager.hasUsageStatsPermission()) {
                AppLogger.w("TrafficTracker: usage stats permission missing")
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.PERMISSION_MISSING,
                        "Usage Access missing",
                    )
                return false
            }
            if (!permissionManager.hasPhoneStatePermission()) {
                AppLogger.w("TrafficTracker: phone state permission missing")
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.PERMISSION_MISSING,
                        "Phone State missing",
                    )
                return false
            }
            return true
        }

        private fun handleSecurityException(e: SecurityException) {
            AppLogger.e("SecurityException in TrafficTracker", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
        }

        private fun handleNoTarget() {
            AppLogger.d("TrafficTracker: no active SIM detected for traffic tracking")
            _status.value = TrackingStatus.Idle
        }

        @Suppress("ReturnCount")
        private suspend fun processTrafficUpdate(target: TargetInfo) {
            val targetIdentity = simIdentityRepository.getIdentity(target.slot)

            val usageRes = readWithRetry({ simUsageRepository.getSimUsage(targetIdentity) }, "simUsage", targetIdentity)
            if (usageRes.isFailure) {
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Timeout reading simUsage")
                return
            }
            val usage = usageRes.getOrThrow()

            val currentBytes = getCurrentMobileBytes(usage.lastResetDate, usage.autoResetDay)

            if (handleBaselineRetry(target, targetIdentity, currentBytes)) return

            if (currentBytes == -1L) {
                handleStatsUnavailable(targetIdentity)
                return
            }

            val lastTotalBytesRes =
                readWithRetry(
                    { simUsageResetRepository.getLastTotalBytes(targetIdentity) },
                    "lastTotalBytes",
                    targetIdentity,
                )
            if (lastTotalBytesRes.isFailure) {
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Timeout reading lastTotalBytes")
                return
            }
            val lastTotalBytes = lastTotalBytesRes.getOrThrow()

            if (lastTotalBytes == -1L || (currentBytes < lastTotalBytes)) {
                handleFirstRunOrReset(targetIdentity, currentBytes)
            } else {
                handleTrafficDelta(targetIdentity, currentBytes, lastTotalBytes, target.slot)
            }

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
        }

        @Suppress("ReturnCount")
        private suspend fun handleBaselineRetry(
            target: TargetInfo,
            targetIdentity: String,
            currentBytes: Long,
        ): Boolean {
            if (currentBytes != -1L) {
                if (baselineNeedsRetry.remove(targetIdentity) == true) {
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
                    return true
                }
            } else if (baselineNeedsRetry[targetIdentity] == true) {
                AppLogger.w("Stats still unavailable for $targetIdentity, will retry baseline reset later")
                _status.value = TrackingStatus.Idle
                return true
            }
            return false
        }

        private fun handleStatsUnavailable(targetIdentity: String) {
            AppLogger.w("Traffic stats unavailable for $targetIdentity")
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Stats unavailable")
        }

        private suspend fun handleFirstRunOrReset(
            identity: String,
            currentBytes: Long,
        ) {
            simUsageResetRepository.setLastTotalBytes(
                identity,
                currentBytes,
            )
            AppLogger.d("Traffic baseline initialized/reset for $identity to $currentBytes")
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

        private fun getCurrentMobileBytes(
            startTime: Long,
            autoResetDay: Int,
        ): Long {
            val now = timeProvider.now()
            // Limit safeStart to a maximum of 30 days back to avoid performance issues
            // with NetworkStatsManager on first run or after a long period of inactivity.
            val lookbackStart = now - (Constants.Traffic.LOOKBACK_DAYS * Constants.MILLIS_PER_DAY)
            val calculatedStart =
                if (startTime > 0) {
                    startTime
                } else {
                    BillingPeriodCalculator.calculate(autoResetDay, now).startDate
                }
            val safeStart = maxOf(calculatedStart, lookbackStart)

            return networkStatsProvider.queryMobileBytes(safeStart, now)
        }

        private suspend fun getTargetSimInfo(): TargetInfo? {
            val (targetSim, currentDataSubId, isFallback) = resolveTargetSim() ?: return null

            ensureInMemoryLastDataSubId()

            val savedLastDataSubId = inMemoryLastDataSubId.get()
            val isSimChanged =
                savedLastDataSubId != -1 && currentDataSubId != -1 && savedLastDataSubId != currentDataSubId

            if (isSimChanged) {
                handleSimChange(targetSim, currentDataSubId, savedLastDataSubId)
                if (isFallback) {
                    AppLogger.w("SIM change detected with fallback attribution to slot $targetSim")
                }
            }

            return TargetInfo(targetSim, currentDataSubId, skipDelta = isSimChanged, isFallback = isFallback)
        }

        @Suppress("ReturnCount")
        private suspend fun resolveTargetSim(): Triple<Int, Int, Boolean>? {
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
                val activeSims = simIdentityRepository.observeActiveSims().firstWithTimeout() ?: emptyList()
                val defaultDataSim = activeSims.find { it.isDefaultData }

                if (defaultDataSim != null) {
                    targetSim = defaultDataSim.slotIndex
                    currentDataSubId = defaultDataSim.subscriptionId
                } else {
                    val activeSubs = permissionManager.getActiveSubscriptions()
                    activeSubs.minByOrNull { it.simSlotIndex }?.let { selected ->
                        targetSim = selected.simSlotIndex
                        currentDataSubId = selected.subscriptionId
                        isFallback = true
                        AppLogger.w(
                            "No default data SIM identified for traffic tracking. " +
                                "Using fallback heuristic: attributing all traffic to Slot $targetSim.",
                        )
                    }
                }
            }

            return if (targetSim != -1) Triple(targetSim, currentDataSubId, isFallback) else null
        }

        private suspend fun ensureInMemoryLastDataSubId() {
            if (inMemoryLastDataSubId.get() == -1) {
                val savedId = callSessionRepository.getLastDataSubId().firstWithTimeout() ?: -1
                inMemoryLastDataSubId.compareAndSet(-1, savedId)
            }
        }

        private suspend fun <T> readWithRetry(
            flowProvider: () -> kotlinx.coroutines.flow.Flow<T>,
            fieldName: String,
            identity: String,
        ): Result<T> {
            suspend fun tryRead(timeout: Long): Result<T>? =
                try {
                    withTimeoutOrNull(timeout.milliseconds) {
                        Result.success(flowProvider().first())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Result.failure(Exception("Timeout reading $fieldName"))
                }

            val firstResult = tryRead(Constants.Flow.SHORT_TIMEOUT_MS)
            if (firstResult != null) return firstResult

            AppLogger.w("TrafficTracker: Timeout reading $fieldName for $identity, retrying with 10s...")
            return tryRead(Constants.Flow.LONG_TIMEOUT_MS) ?: Result.failure(Exception("Timeout reading $fieldName"))
        }

        private suspend fun handleSimChange(
            targetSim: Int,
            currentDataSubId: Int,
            savedLastDataSubId: Int,
        ) {
            AppLogger.d(
                "Default data SIM changed from $savedLastDataSubId to $currentDataSubId. " +
                    "Resetting baseline.",
            )
            try {
                val identity = simIdentityRepository.getIdentity(targetSim)
                val usage = simUsageRepository.getSimUsage(identity).firstWithTimeout()
                val currentBytes =
                    if (usage != null) {
                        try {
                            getCurrentMobileBytes(usage.lastResetDate, usage.autoResetDay)
                        } catch (e: Exception) {
                            -1L
                        }
                    } else {
                        -1L
                    }

                if (currentBytes != -1L) {
                    simUsageResetRepository.setLastTotalBytes(
                        identity,
                        currentBytes,
                    )
                    AppLogger.d("Baseline reset successfully for $identity")
                } else {
                    AppLogger.w("Stats unavailable on SIM change for $identity. Baseline reset will be retried.")
                    if (baselineNeedsRetry.size >= SimConstants.MAX_SUPPORTED_SIM_SLOTS) {
                        val activeIdentities =
                            simIdentityRepository.getActiveIdentities().firstWithTimeout() ?: emptyList()
                        baselineNeedsRetry.keys.removeAll { it !in activeIdentities }
                    }
                    baselineNeedsRetry[identity] = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to handle SIM change", e)
            } finally {
                // Break the isSimChanged loop regardless of baseline reset success.
                // This ensures skipDelta is true only for the very first attempt.
                callSessionRepository.setLastDataSubId(currentDataSubId)
                inMemoryLastDataSubId.set(currentDataSubId)
            }
        }
    }
