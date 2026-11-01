package host.serv.djbigmuh.minutewatcher.core.data

import android.os.Build
import android.telephony.TelephonyManager
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for resolving call completion in ambiguous situations.
 * Extracted from [CallTracker] to reduce complexity.
 */
@Singleton
internal class CallEndResolver
    @Inject
    constructor(
        private val callSessionRepository: CallSessionRepository,
        private val permissionManager: PermissionManager,
        private val telephonyManager: TelephonyManager?,
    ) {
        sealed interface ResolveResult {
            data class SubIds(
                val ids: List<Int>,
            ) : ResolveResult

            data class Error(
                val error: TrackingStatus.Error,
            ) : ResolveResult
        }

        /**
         * Resolves the target subscription ID(s) when a call end event is received with an unknown subId.
         *
         * Decision Matrix:
         * - Missing [android.Manifest.permission.READ_PHONE_STATE] -> [ResolveResult.Error]
         *   with [TrackingStatus.ErrorType.PERMISSION_MISSING].
         * - [CallSessionRepository.getActiveCallSubIds] returns `null` (error/timeout) -> [ResolveResult.Error]
         *   with [TrackingStatus.ErrorType.UNKNOWN].
         * - [CallSessionRepository.getActiveCallSubIds] returns empty list -> [ResolveResult.SubIds] with empty list.
         * - [TelephonyManager] is null -> [ResolveResult.SubIds] containing all active subIds.
         * - API 31+ (Android S+): All active subscriptions are [TelephonyManager.CALL_STATE_IDLE] ->
         *   [ResolveResult.SubIds] with all active subIds.
         * - API 31+: Single active session -> [ResolveResult.SubIds] with that single subId (fast-path).
         * - API 31+: Single active session, telephony query throws [RuntimeException] ->
         *   [ResolveResult.SubIds] with that single subId.
         * - API 31+: Multiple active sessions, exactly one in [TelephonyManager.CALL_STATE_IDLE] ->
         *   [ResolveResult.SubIds] with that single IDLE subId.
         * - API 31+: Multiple active sessions, no subIds in [TelephonyManager.CALL_STATE_IDLE] ->
         *   [ResolveResult.Error] with [TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION].
         * - API 31+: Multiple active sessions, two or more in [TelephonyManager.CALL_STATE_IDLE] ->
         *   [ResolveResult.Error] with [TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION].
         * - API < 31: Global [TelephonyManager.getCallState] is [TelephonyManager.CALL_STATE_IDLE] ->
         *   [ResolveResult.SubIds] with all active subIds.
         * - API < 31: Global callState is not IDLE, single active session ->
         *   [ResolveResult.SubIds] with that single subId.
         * - API < 31: Global callState is not IDLE, multiple active sessions ->
         *   [ResolveResult.Error] with [TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION].
         * - [SecurityException] during active subIds fetch -> rethrown to caller.
         *
         * Fast-path rule for single active session:
         * For a single active session, the session is closed without additional subscription state
         * verification (fast-path) because call duration is computed using the event's `elapsedRealtime`
         * captured when the broadcast was received, and a subsequent `OFFHOOK` for a new call would create
         * a new session.
         *
         * Note on multiple active sessions:
         * When multiple active sessions exist and two or more are in IDLE state (without all being IDLE),
         * it is treated as an ambiguous attribution.
         *
         * @return [ResolveResult] representing either resolved subIds or an error.
         * @throws SecurityException if permission is revoked during session lookup.
         */
        suspend fun resolveUnknownSubIdEnd(): ResolveResult {
            if (!permissionManager.hasPhoneStatePermission()) {
                AppLogger.w("CallEndResolver: permission revoked during resolveUnknownSubIdEnd")
                return ResolveResult.Error(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.PERMISSION_MISSING,
                        "Permission revoked during call end resolution",
                    ),
                )
            }

            val activeSubIds = fetchActiveSubIds()
            return when {
                activeSubIds == null -> {
                    ResolveResult.Error(
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "Timeout reading active call sessions",
                        ),
                    )
                }

                activeSubIds.isEmpty() -> {
                    AppLogger.d("Call ended with unknown subId and no active sessions found")
                    ResolveResult.SubIds(emptyList())
                }

                isDeviceIdle(activeSubIds) -> {
                    AppLogger.d("Call ended with unknown subId and global state is IDLE. Closing all sessions.")
                    ResolveResult.SubIds(activeSubIds)
                }

                else -> {
                    val target = findTargetSubId(activeSubIds)
                    if (target != null) {
                        ResolveResult.SubIds(listOf(target))
                    } else {
                        ResolveResult.Error(handleAmbiguousIdle(activeSubIds))
                    }
                }
            }
        }

    private suspend fun fetchActiveSubIds(): List<Int>? =
        try {
                callSessionRepository.getActiveCallSubIds()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
                AppLogger.e("Failed to read active call sessions on unknown subId end", e)
                null
            }

        /**
         * Queries call state for a subscription ID.
         * Suppressing MissingPermission and TooGenericExceptionCaught due to permission check
         * and runtime exception handling.
         */
        @Suppress("MissingPermission")
        private fun getCallStateForSub(subId: Int): Int? {
            val manager = telephonyManager ?: return null
            if (!permissionManager.hasPhoneStatePermission()) {
                AppLogger.w("Permission not granted, skipping telephony callState query")
                return null
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    manager.createForSubscriptionId(subId).callStateForSubscription
                } else {
                    @Suppress("DEPRECATION") // Legacy pre-S API
                    manager.callState
                }
            } catch (e: SecurityException) {
                AppLogger.w("SecurityException while querying call state for subId $subId", e)
                null
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                AppLogger.w("RuntimeException from telephony service for subId $subId", e)
                null
            }
        }

        private fun isDeviceIdle(activeSubIds: List<Int>): Boolean {
            val manager = telephonyManager
            val hasPermission = permissionManager.hasPhoneStatePermission()

            return when {
                manager == null -> true
                !hasPermission -> {
                    AppLogger.w("Permission not granted, skipping API call for isDeviceIdle")
                    false
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    activeSubIds.all { id ->
                        getCallStateForSub(id) == TelephonyManager.CALL_STATE_IDLE
                    }
                }

                else -> {
                    getCallStateForSub(activeSubIds.firstOrNull() ?: -1) == TelephonyManager.CALL_STATE_IDLE
                }
            }
        }

        private fun handleAmbiguousIdle(activeSubIds: List<Int>): TrackingStatus.Error {
            val message = "Cannot attribute unknown IDLE event for multiple active calls (${activeSubIds.size})"
            AppLogger.e(message)
            return TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION, message)
        }

        /**
         * Identifies the specific target subscription ID among multiple active sessions.
         *
         * Fast-path rule for single active session:
         * When [activeSubIds] has exactly one element, that single session is returned immediately (fast-path)
         * without additional subscription state verification, as call duration is calculated using the event's
         * `elapsedRealtime` recorded at broadcast reception time, and any subsequent `OFFHOOK` for a new call
         * creates a new session.
         *
         * On API 31+ (Android S+), if there are multiple active sessions, exactly one subscription must be in
         * [TelephonyManager.CALL_STATE_IDLE]. Having two or more (or zero) IDLE states among multiple sessions
         * is treated as ambiguous attribution.
         *
         * @param activeSubIds List of active call subscription IDs.
         * @return The single target subscription ID, or `null` if ambiguous or unsupported.
         */
        private fun findTargetSubId(activeSubIds: List<Int>): Int? {
            val manager = telephonyManager
            val hasPermission = permissionManager.hasPhoneStatePermission()

            return when {
                manager == null -> null
                !hasPermission -> {
                    AppLogger.w("Permission not granted, skipping API call for findTargetSubId")
                    null
                }

                activeSubIds.size == 1 -> activeSubIds.first()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    activeSubIds.singleOrNull { id ->
                        getCallStateForSub(id) == TelephonyManager.CALL_STATE_IDLE
                    }
                }

                else -> null
            }
        }
    }
