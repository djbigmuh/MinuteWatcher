package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.CallEventWorker
import kotlinx.coroutines.CancellationException

class UsageMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action

        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val appContext = context.applicationContext

            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

            // Try to extract subId using various possible keys for better compatibility
            val subId =
                intent.getIntExtra("subscription", -1).let {
                    if (it == -1) intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_ID", -1) else it
                }

            try {
                val data =
                    Data
                        .Builder()
                        .putString("state", state)
                        .putInt("subId", subId)
                        .build()

                val workRequest =
                    OneTimeWorkRequestBuilder<CallEventWorker>()
                        .setInputData(data)
                        .build()

                val uniqueWorkName =
                    if (subId != -1) {
                        "CALL_EVENT_$subId"
                    } else {
                        "CALL_EVENT_UNKNOWN"
                    }

                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    uniqueWorkName,
                    ExistingWorkPolicy.APPEND,
                    workRequest,
                )
                AppLogger.d("UsageMonitorReceiver: CallEventWorker enqueued for state=$state, subId=$subId")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("UsageMonitorReceiver: Failed to enqueue CallEventWorker", e)
            }
        }
    }
}
