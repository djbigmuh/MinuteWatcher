package com.example.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
internal class CallAttributionResolver
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val permissionManager: PermissionManager,
        private val dataChangeNotifier: DataChangeNotifier,
    ) {
        private val attributionFailures = ConcurrentHashMap<Int, Int>()

        fun retainOnlyActiveSubIds(activeSubIds: Set<Int>) {
            attributionFailures.entries.removeIf { it.key !in activeSubIds }
        }

        suspend fun resolveTargetIdentity(
            subId: Int,
            savedIdentity: String?,
        ): Pair<String?, Boolean> {
            var targetIdentity = savedIdentity ?: resolveIdentity(subId)
            var isFallbackUsed = false

            if (targetIdentity == null) {
                val activeSims = simIdentityRepository.observeActiveSims().firstOrNull().orEmpty()
                val matched = activeSims.find { it.subscriptionId == subId }
                if (matched != null) {
                    targetIdentity = matched.identity.stableId
                } else if (activeSims.size == 1) {
                    val fallbackSim = activeSims.first()
                    targetIdentity = fallbackSim.identity.stableId
                    isFallbackUsed = true
                    AppLogger.w(
                        "CallTracker: no SIM matched subId=$subId, single active SIM fallback used $targetIdentity",
                    )
                } else if (activeSims.size > 1) {
                    AppLogger.w(
                        "CallTracker: no SIM matched subId=$subId and " +
                            "multiple active SIMs present, fallback refused",
                    )
                }
            }
            return targetIdentity to isFallbackUsed
        }

        suspend fun handleAttributionFailure(
            subId: Int,
            updateStatus: (TrackingStatus) -> Unit,
            cleanupAction: suspend (Int) -> Unit,
        ): Boolean {
            val failures: Int = checkNotNull(attributionFailures.merge(subId, 1) { current, _ -> current + 1 })
            if (failures >= MAX_ATTRIBUTION_FAILURES) {
                AppLogger.e("CallTracker: max attribution failures reached for subId $subId, clearing session")
                cleanupAction(subId)
                return true
            }
            AppLogger.w("CallTracker: could not attribute call for subId $subId, attempt $failures")
            updateStatus(
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                    "Could not attribute call to SIM identity",
                ),
            )
            dataChangeNotifier.notifyDataChanged()
            return false
        }

        fun resolveTargetSubId(subId: Int): Int =
            if (subId == -1) {
                val default = permissionManager.getDefaultVoiceSubscriptionId()
                if (default != -1) {
                    AppLogger.d("CallTracker: attributed unknown subId start event to default voice SIM ($default)")
                    default
                } else {
                    -1
                }
            } else {
                subId
            }

        suspend fun resolveSessionIdentity(subId: Int): String? =
            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                simIdentityRepository
                    .observeActiveSims()
                    .firstOrNull()
                    ?.find { it.subscriptionId == subId }
                    ?.identity
                    ?.stableId
            }

        suspend fun resolveIdentity(subId: Int): String? =
            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                simIdentityRepository
                    .observeActiveSims()
                    .firstOrNull()
                    ?.find { it.subscriptionId == subId && it.identity.isStable }
                    ?.identity
                    ?.stableId
            }

        fun clearFailures(subId: Int) {
            attributionFailures.remove(subId)
        }

        fun clearAllFailures() {
            attributionFailures.clear()
        }

        @VisibleForTesting
        fun getFailureCount(subId: Int): Int = attributionFailures[subId] ?: 0

        @VisibleForTesting
        fun setFailureCountForTest(
            subId: Int,
            count: Int,
        ) {
            attributionFailures[subId] = count
        }

        companion object {
            private const val MAX_ATTRIBUTION_FAILURES = 3
        }
    }
