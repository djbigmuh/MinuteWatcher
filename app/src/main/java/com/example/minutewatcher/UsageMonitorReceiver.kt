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
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class UsageMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action

        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val subId = intent.getIntExtra("subscription", -1)

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

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "CALL_EVENT",
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
        }
    }
}
