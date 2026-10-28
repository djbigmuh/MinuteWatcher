package host.serv.djbigmuh.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Resolves SIM identities and handles attribution for call tracking.
 *
 * Enforces the rule that both call duration (minutes) and data traffic attribution
 * require a stable SIM identity ([host.serv.djbigmuh.minutewatcher.core.model.SimIdentity.isStable] == true).
 * Unstable SIM identities (e.g., generated solely from subscription ID without ICCID) are rejected
 * for attribution to prevent misattributing usage across SIM replacements.
 */
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

        /**
         * Resolves the target identity for a call event.
         *
         * Resolution order:
         * 1. [savedIdentity] recorded for the session, when present.
         * 2. Stable identity of the active SIM matching [subId] (primary read).
         * 3. Re-read of the active SIMs list: stable identity of the SIM matching
         *    [subId]. An exact subscription match with a stable identity is precise
         *    attribution, not a fallback.
         * 4. Fallback: exactly one active SIM with a stable identity is present.
         *
         * Each read of the active SIMs snapshot is performed with a bounded wait
         * ([Constants.Flow.SHORT_TIMEOUT_MS]); on timeout the identity remains
         * unresolved and the caller applies the standard attribution failure
         * handling. Unstable identities are never used for attribution.
         *
         * @return pair of the resolved stable identity (or null when unresolved)
         * and a flag that is true only when the single-active-SIM fallback was used.
         */
        suspend fun resolveTargetIdentity(
            subId: Int,
            savedIdentity: String?,
        ): Pair<String?, Boolean> {
            var targetIdentity = savedIdentity ?: resolveIdentity(subId)
            var isFallbackUsed = false

            if (targetIdentity == null) {
                val activeSims =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        simIdentityRepository.observeActiveSims().firstOrNull()
                    } ?: run {
                        AppLogger.w("CallTracker: timeout reading active SIMs for attribution of subId=$subId")
                        emptyList()
                    }
                val matched = activeSims.find { it.subscriptionId == subId }
                if (matched != null) {
                    if (subId != -1 && matched.identity.isStable) {
                        targetIdentity = matched.identity.stableId
                        AppLogger.d(
                            "CallTracker: identity resolved from active SIM matched by subId=$subId",
                        )
                    } else {
                        AppLogger.w(
                            "CallTracker: SIM matched subId=$subId but identity is unstable, " +
                                "attribution refused",
                        )
                    }
                } else if (activeSims.size == 1) {
                    val fallbackSim = activeSims.first()
                    if (fallbackSim.identity.isStable) {
                        targetIdentity = fallbackSim.identity.stableId
                        isFallbackUsed = true
                        AppLogger.w(
                            "CallTracker: no SIM matched subId=$subId, single active SIM fallback used $targetIdentity",
                        )
                    } else {
                        AppLogger.w(
                            "CallTracker: no SIM matched subId=$subId, single active SIM is unstable, fallback refused",
                        )
                    }
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
            if (failures >= Constants.Calls.ATTRIBUTION_FAILURE_LIMIT) {
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

        /**
         * Resolves the stable SIM identity for the given subscription ID.
         *
         * Consistent with traffic attribution, returns a non-null stable ID only if the active SIM's
         * identity is stable ([host.serv.djbigmuh.minutewatcher.core.model.SimIdentity.isStable] == true).
         */
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
        internal fun getFailureCount(subId: Int): Int = attributionFailures[subId] ?: 0

        @VisibleForTesting
        internal fun setFailureCount(
            subId: Int,
            count: Int,
        ) {
            attributionFailures[subId] = count
        }
    }
