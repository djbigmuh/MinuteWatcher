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
import com.example.minutewatcher.core.domain.DataChangeNotifier
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
    private val dataChangeNotifier: DataChangeNotifier,
    private val endCall: suspend (Int, Boolean) -> Unit,
    private val setError: (TrackingStatus.Error) -> Unit,
) {
    suspend fun resolveUnknownSubIdEnd() {
        if (!permissionManager.hasPhoneStatePermission()) {
            AppLogger.w("CallEndResolver: permission revoked during resolveUnknownSubIdEnd")
            setError(
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.PERMISSION_MISSING,
                    "Permission revoked during call end resolution",
                ),
            )
            dataChangeNotifier.notifyDataChanged()
            return
        }
        val activeSubIds =
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

        if (activeSubIds.isEmpty()) {
            AppLogger.d("Call ended with unknown subId and no active sessions found")
        } else if (isDeviceIdle(activeSubIds)) {
            handleGlobalIdle(activeSubIds)
        } else {
            val target: Int? = findTargetSubId(activeSubIds)
            if (target != null) {
                endCall(target, true)
            } else {
                handleAmbiguousIdle(activeSubIds)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun isDeviceIdle(activeSubIds: List<Int>): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activeSubIds.all { id ->
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_PHONE_STATE,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val state = telephonyManager.createForSubscriptionId(id).callStateForSubscription
                    state == TelephonyManager.CALL_STATE_IDLE
                } else {
                    AppLogger.w("Permission not granted, skipping API call for isDeviceIdle")
                    false
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val state = telephonyManager.callState
                state == TelephonyManager.CALL_STATE_IDLE
            } else {
                AppLogger.w("Permission not granted, skipping API call for isDeviceIdle (legacy)")
                false
            }
        }

    private suspend fun handleGlobalIdle(activeSubIds: List<Int>) {
        AppLogger.d("Call ended with unknown subId and global state is IDLE. Closing all sessions.")
        for (subId in activeSubIds) {
            endCall(subId, false)
        }
        dataChangeNotifier.notifyDataChanged()
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
                activeSubIds
                    .asSequence()
                    .filter { id ->
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_PHONE_STATE,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            val state = telephonyManager.createForSubscriptionId(id).callStateForSubscription
                            state == TelephonyManager.CALL_STATE_IDLE
                        } else {
                            false
                        }
                    }.singleOrNull()
            }
            else -> null
        }
    }
}
