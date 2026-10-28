package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.firstWithTimeoutOrLog
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles SIM-change transitions for the data SIM that [TrafficTracker] attributes traffic to.
 *
 * **Invariant:** Delta updates are only allowed when the previous data subscription ID
 * is reliably known. If it's unknown (e.g., due to storage timeout during initialization),
 * a conservative baseline reset is performed to prevent misattribution.
 */
@Singleton
class TrafficSimChangeHandler
    @Inject
    constructor(
        private val callSessionRepository: CallSessionRepository,
        private val simIdentityRepository: SimIdentityRepository,
        private val baselineManager: TrafficBaselineManager,
    ) {
        private val simChangeMutex = Mutex()
        private val inMemoryLastDataSubId = AtomicInteger(-1)
        private val lastDataSubIdLoaded = AtomicBoolean(false)

        suspend fun <T> withSimChangeLock(block: suspend () -> T): T = simChangeMutex.withLock { block() }

        suspend fun ensureInMemoryLastDataSubId() {
            if (!lastDataSubIdLoaded.get()) {
                try {
                    val loaded =
                        callSessionRepository
                            .getLastDataSubId()
                            .firstWithTimeoutOrLog(
                                timeoutMillis = Constants.Flow.LONG_TIMEOUT_MS,
                                tag = "TrafficSimChangeHandler",
                                key = "lastDataSubId",
                            )
                    if (loaded != null) {
                        inMemoryLastDataSubId.set(loaded)
                        lastDataSubIdLoaded.set(true)
                    } else {
                        AppLogger.w("TrafficSimChangeHandler: Timeout loading lastDataSubId")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("TrafficSimChangeHandler: Failed to load lastDataSubId", e)
                }
            }
        }

        suspend fun handleSimChangeIfNeeded(
            target: TargetInfo,
            updateStatus: (TrackingStatus) -> Unit,
        ): String? {
            ensureInMemoryLastDataSubId()
            val savedLastDataSubId = inMemoryLastDataSubId.get()
            val targetIdentity = simIdentityRepository.getIdentity(target.slot)

            if (!lastDataSubIdLoaded.get()) {
                AppLogger.d(
                    "TrafficSimChangeHandler: previous subId unknown (load failed). " +
                        "Performing conservative baseline reset for $targetIdentity.",
                )
                // Force conservative reset path
                handleSimChange(
                    identity = targetIdentity,
                    currentDataSubId = target.dataSubId,
                    savedLastDataSubId = -1,
                    updateStatus = updateStatus,
                    isSimChanged = true,
                )
                return null // Skip delta
            }

            val isSimChanged =
                (savedLastDataSubId != -1) &&
                    (target.dataSubId != -1) &&
                    (savedLastDataSubId != target.dataSubId)

            // We always call handleSimChange to ensure the retry map is cleaned up for other identities.
            // Logic for actual SIM change processing is contained within handleSimChange.
            handleSimChange(
                targetIdentity,
                target.dataSubId,
                savedLastDataSubId,
                updateStatus,
                isSimChanged,
            )

            return if (isSimChanged) {
                handleSkipDelta(target)
                null
            } else {
                targetIdentity
            }
        }

        private fun handleSkipDelta(target: TargetInfo) {
            AppLogger.d("Traffic delta skipped due to SIM change (slot=${target.slot})")
            // We do NOT update status to Active here, because handleSimChange already
            // triggered a baseline reset which sets the status (Active or Error).
        }

        private suspend fun handleSimChange(
            identity: String,
            currentDataSubId: Int,
            savedLastDataSubId: Int,
            updateStatus: (TrackingStatus) -> Unit,
            isSimChanged: Boolean,
        ) {
            // Centralized cleanup: remove all retries for other identities
            baselineManager.removeRetriesExcept(identity)

            if (!isSimChanged) return

            AppLogger.d("Default data SIM changed from $savedLastDataSubId to $currentDataSubId. Resetting baseline.")
            try {
                // Update the last seen data subId immediately to "commit" the detection of the SIM change.
                // This prevents the same SIM change from being repeatedly detected if the baseline
                // reset operation fails and enters a retry cycle.
                updateLastDataSubId(currentDataSubId)

                // Use resetBaseline which correctly queries [lastResetDate, now] window.
                // It also handles retries and TrackingStatus internally.
                baselineManager.resetBaseline(
                    identity = identity,
                    updateStatus = updateStatus,
                    incrementRetryOnFailure = true,
                ) {
                    // Success callback: identity and dataSubId are already in sync.
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to handle SIM change", e)
                updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message))
            }
        }

        suspend fun updateLastDataSubId(dataSubId: Int) {
            if (dataSubId == -1) {
                // Invalid value: do not touch memory or the loaded flag.
                return
            }
            if (lastDataSubIdLoaded.get() && dataSubId == inMemoryLastDataSubId.get()) return
            inMemoryLastDataSubId.set(dataSubId)
            lastDataSubIdLoaded.set(true)
            try {
                callSessionRepository.setLastDataSubId(dataSubId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficSimChangeHandler: Failed to persist last data subId $dataSubId", e)
                // in-memory value remains: detecting SIM change is more important than persistence
            }
        }
    }
