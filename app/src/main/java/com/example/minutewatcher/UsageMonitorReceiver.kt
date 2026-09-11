package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.CallEventWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import java.util.concurrent.TimeUnit

open class UsageMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action

        if (action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        goAsyncWithScope(context, acquirePendingResult()) { appContext ->
            try {
                processPhoneStateChange(appContext, intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("UsageMonitorReceiver: Failed to enqueue CallEventWorker", e)
            }
        }
    }

    @VisibleForTesting
    protected open fun acquirePendingResult(): BroadcastReceiver.PendingResult = goAsync()

    /**
     * Processes the phone state change and enqueues a [CallEventWorker] to handle it.
     *
     * We use [ExistingWorkPolicy.APPEND_OR_REPLACE] to ensure that:
     * 1. Events for the same subId are processed in the order they were received.
     * 2. If a previous worker in the chain fails (e.g., due to a [SecurityException]
     *    or unexpected error), the chain is not "poisoned". Subsequent events will
     *    still be enqueued and executed as a new chain, rather than being
     *    automatically failed as they would with [ExistingWorkPolicy.APPEND].
     *
     * @see <a href="https://developer.android.com/reference/androidx/work/ExistingWorkPolicy#APPEND">APPEND</a>
     */
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
                .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, SystemClock.elapsedRealtime())
                .build()

        val workRequest =
            OneTimeWorkRequestBuilder<CallEventWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
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
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest,
            ).result
            .await() // Ensure enqueuing completes before finish()
        AppLogger.d("UsageMonitorReceiver: CallEventWorker enqueued for state=$state, subId=$subId")
    }
}
