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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вспомогательный класс для разрешения завершения звонков в неопределенных ситуациях.
 * Вынесен из [CallTracker] для уменьшения сложности.
 */
@Singleton
internal class CallEndResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val callSessionRepository: CallSessionRepository,
        private val permissionManager: PermissionManager,
        private val telephonyManager: TelephonyManager,
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

        private fun handleAmbiguousIdle(activeSubIds: List<Int>): TrackingStatus.Error {
            val message = "Cannot attribute unknown IDLE event for multiple active calls (${activeSubIds.size})"
            AppLogger.e(message, null)
            return TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
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
