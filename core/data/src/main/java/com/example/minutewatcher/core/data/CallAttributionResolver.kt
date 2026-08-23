package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val callSessionRepository: CallSessionRepository,
    private val permissionManager: PermissionManager,
    private val retryHelper: DataStoreRetryHelper,
    private val dataChangeNotifier: DataChangeNotifier,
) {
    private val attributionFailures = ConcurrentHashMap<Int, Int>()

    fun getAttributionFailures(): ConcurrentHashMap<Int, Int> = attributionFailures

    suspend fun resolveTargetIdentity(
        subId: Int,
        savedIdentity: String?,
    ): Pair<String?, Boolean> {
        var targetIdentity = savedIdentity ?: resolveIdentity(subId)
        var isFallbackUsed = false

        if (targetIdentity == null) {
            val activeSims = simIdentityRepository.observeActiveSims().firstOrNull() ?: emptyList()
            val matched = activeSims.find { it.subscriptionId == subId }
            if (matched != null) {
                targetIdentity = matched.identity.stableId
            } else if (activeSims.isNotEmpty()) {
                // Fallback допустим только когда слот не идентифицирован.
                // Берём первую активную, но фиксируем неточность.
                targetIdentity = activeSims.first().identity.stableId
                isFallbackUsed = true
                AppLogger.w("CallTracker: no SIM matched subId=$subId, using first active as fallback")
            }
        }
        return targetIdentity to isFallbackUsed
    }

    suspend fun handleAttributionFailure(
        subId: Int,
        statusFlow: MutableStateFlow<TrackingStatus>,
        cleanupAction: suspend (Int) -> Unit,
    ): Boolean {
        val failures = (attributionFailures[subId] ?: 0) + 1
        attributionFailures[subId] = failures
        if (failures >= MAX_ATTRIBUTION_FAILURES) {
            AppLogger.e("CallTracker: max attribution failures reached for subId $subId, clearing session")
            cleanupAction(subId)
            return true
        }
        AppLogger.w("CallTracker: could not attribute call for subId $subId, attempt $failures")
        statusFlow.value =
            TrackingStatus.Error(
                TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                "Could not attribute call to SIM identity",
            )
        dataChangeNotifier.notifyDataChanged()
        return false
    }

    suspend fun resolveEffectiveSubId(subId: Int): Int {
        if (subId != -1) return subId

        // 1. Try last data SIM
        val lastResult =
            retryHelper.readWithRetry(
                label = "resolveEffectiveSubId:lastData",
            ) { callSessionRepository.getLastDataSubId() }
        val last = lastResult.getOrElse { -1 }
        if (last != -1) {
            AppLogger.d("CallTracker: attributed unknown subId event to last data SIM ($last)")
            return last
        }

        return resolveEffectiveSubIdFromActiveCalls()
    }

    suspend fun resolveEffectiveSubIdFromActiveCalls(): Int {
        // 2. Heuristic: if only one call is active, it must be it
        val active =
            try {
                callSessionRepository.getActiveCallSubIds()
            } catch (e: Exception) {
                AppLogger.e("Error reading active call subIds", e)
                emptyList()
            }
        if (active.size == 1) {
            val single = active.first()
            AppLogger.d("CallTracker: attributed unknown subId event to single active call ($single)")
            return single
        }

        return -1
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

    suspend fun resolveIdentity(subId: Int): String? =
        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
            simIdentityRepository
                .observeActiveSims()
                .firstOrNull()
                ?.find { it.subscriptionId == subId }
                ?.identity
                ?.stableId
        }

    fun clearFailures(subId: Int) {
        attributionFailures.remove(subId)
    }

    fun clearAllFailures() {
        attributionFailures.clear()
    }

    companion object {
        private const val MAX_ATTRIBUTION_FAILURES = 3
    }
}
