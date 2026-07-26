package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.data.CallEventWorker
import com.example.minutewatcher.core.model.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class UsageMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action

        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val pendingResult = goAsync()
            val appContext = context.applicationContext

            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val subId = intent.getIntExtra("subscription", -1)

            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
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
                } catch (e: Exception) {
                    AppLogger.e("UsageMonitorReceiver: Failed to enqueue CallEventWorker", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
