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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class UsageMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action

        if (action != TelephonyManager.ACTION_PHONE_STATE_CHANGED &&
            action != "android.telephony.action.PHONE_STATE_CHANGED"
        ) {
            return
        }

        // goAsync() extends the receiver's lifetime until finish() is called.
        // Required because WorkManager.enqueue() may block on disk I/O.
        // Without this, the system may kill the receiver process before
        // the work is actually enqueued.
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        // Строгое соблюдение API: BroadcastReceiver с goAsync() должен вызвать
        // PendingResult.finish() до завершения. SupervisorJob гарантирует, что
        // отмена одной корутины не повлияет на другие. finish() вызывается в
        // finally, поэтому даже при отмене корутины он будет вызван.
        // На AOSP BroadcastReceiver с goAsync() живёт до вызова finish().
        // Если кастомная прошивка убивает процесс до вызова finish() —
        // это проблема кастомной прошивки.
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            try {
                processPhoneStateChange(appContext, intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("UsageMonitorReceiver: Failed to enqueue CallEventWorker", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processPhoneStateChange(
        appContext: Context,
        intent: Intent,
    ) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        if (state == null) {
            AppLogger.w("UsageMonitorReceiver: EXTRA_STATE is null, skipping")
            return
        }

        // Try to extract subId using various possible keys for better compatibility
        val subId =
            intent.getIntExtra("subscription", -1).let {
                if (it == -1) intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_ID", -1) else it
            }

        if (subId == -1) {
            AppLogger.w("UsageMonitorReceiver: subId not found in intent extras")
        }

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

        WorkManager
            .getInstance(appContext)
            .enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.APPEND,
                workRequest,
            ).result
            .await() // Ensure enqueuing completes before finish()
        AppLogger.d("UsageMonitorReceiver: CallEventWorker enqueued for state=$state, subId=$subId")
    }
}
