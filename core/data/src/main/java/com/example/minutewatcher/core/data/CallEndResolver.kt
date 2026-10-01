package com.example.minutewatcher.core.data

import android.os.Build
import android.telephony.TelephonyManager
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.model.TrackingStatus
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
            } catch (e: Exception) {
                AppLogger.e("Failed to read active call sessions on unknown subId end", e)
                null
            }

    @Suppress("MissingPermission", "TooGenericExceptionCaught", "ReturnCount")
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
                @Suppress("DEPRECATION")
                manager.callState
            }
        } catch (e: SecurityException) {
            AppLogger.w("SecurityException while querying call state for subId $subId", e)
            null
        } catch (e: RuntimeException) {
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
            AppLogger.e(message, null)
            return TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION, message)
        }

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
