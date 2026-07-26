package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.example.minutewatcher.core.data.CallEventWorker
import com.example.minutewatcher.core.data.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class UsageMonitorReceiver : BroadcastReceiver() {

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        
        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val pendingResult = goAsync()
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val subId = intent.getIntExtra("subscription", -1)
            
            applicationScope.launch {
                try {
                    val data = Data.Builder()
                        .putString("state", state)
                        .putInt("subId", subId)
                        .build()
                        
                    val workRequest = OneTimeWorkRequestBuilder<CallEventWorker>()
                        .setInputData(data)
                        .build()
                        
                    WorkManager.getInstance(context.applicationContext).enqueue(workRequest).await()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
