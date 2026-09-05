package com.example.minutewatcher.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException

/**
 * Вспомогательный класс для разрешения завершения звонков в неопределенных ситуациях.
 * Вынесен из [CallTracker] для уменьшения сложности.
 */
internal class CallEndResolver(
    private val context: Context,
    private val callSessionRepository: CallSessionRepository,
    private val permissionManager: PermissionManager,
    private val telephonyManager: TelephonyManager,
    private val setError: (TrackingStatus.Error) -> Unit,
) {
    suspend fun resolveUnknownSubIdEnd(): List<Int> {
        if (!permissionManager.hasPhoneStatePermission()) {
            AppLogger.w("CallEndResolver: permission revoked during resolveUnknownSubIdEnd")
            setError(
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.PERMISSION_MISSING,
                    "Permission revoked during call end resolution",
                ),
            )
            return emptyList()
        }

        val activeSubIds = fetchActiveSubIds()
        return when {
            activeSubIds.isEmpty() -> {
                AppLogger.d("Call ended with unknown subId and no active sessions found")
                emptyList()
            }

            isDeviceIdle(activeSubIds) -> {
                AppLogger.d("Call ended with unknown subId and global state is IDLE. Closing all sessions.")
                activeSubIds
            }

            else -> {
                val target = findTargetSubId(activeSubIds)
                if (target != null) {
                    listOf(target)
                } else {
                    handleAmbiguousIdle(activeSubIds)
                    emptyList()
                }
            }
        }
    }

    private suspend fun fetchActiveSubIds(): List<Int> =
        try {
            callSessionRepository.getActiveCallSubIds()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Failed to read active call sessions on unknown subId end", e)
            emptyList()
        }

    @Suppress("MissingPermission")
    private fun isDeviceIdle(activeSubIds: List<Int>): Boolean {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.w("Permission not granted, skipping API call for isDeviceIdle")
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activeSubIds.all { id ->
                telephonyManager.createForSubscriptionId(id).callStateForSubscription ==
                    TelephonyManager.CALL_STATE_IDLE
            }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE
        }
    }

    private fun handleAmbiguousIdle(activeSubIds: List<Int>) {
        val message = "Cannot attribute unknown IDLE event for multiple active calls (${activeSubIds.size})"
        AppLogger.e(message, null)
        setError(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message))
    }

    @Suppress("MissingPermission")
    private fun findTargetSubId(activeSubIds: List<Int>): Int? {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.w("Permission not granted, skipping API call for findTargetSubId")
            return null
        }

        return when {
            activeSubIds.size == 1 -> activeSubIds.first()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                activeSubIds.singleOrNull { id ->
                    telephonyManager.createForSubscriptionId(id).callStateForSubscription ==
                        TelephonyManager.CALL_STATE_IDLE
                }
            }

            else -> null
        }
    }
}
