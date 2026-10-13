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
import kotlinx.coroutines.CoroutineDispatcher
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

        goAsyncWithScope(context, acquirePendingResult(), dispatcher = asyncDispatcher()) { appContext ->
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

    @VisibleForTesting
    protected open fun asyncDispatcher(): CoroutineDispatcher? = null

    /**
     * Processes the phone state change and enqueues a [CallEventWorker] to handle it.
     *
     * - All call events are processed in a single global unique chain [UNIQUE_WORK_NAME] using
     *   [ExistingWorkPolicy.APPEND_OR_REPLACE] to preserve reception order.
     * - Event timestamp is captured in input data via [SystemClock.elapsedRealtime], ensuring
     *   execution delay or retries do not distort call duration calculations.
     * - On [androidx.work.ListenableWorker.Result.retry], enqueued subsequent events wait for the
     *   current worker to complete, preserving processing order.
     * - If a worker finishes with [androidx.work.ListenableWorker.Result.failure], WorkManager cancels
     *   enqueued dependents of that work chain. Events enqueued AFTER the chain transitions to a terminal
     *   state start a new chain.
     * - Orphaned call sessions are cleaned up upon device reboot and when a stale session is detected
     *   by the next call start event.
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

        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            AppLogger.d("UsageMonitorReceiver: EXTRA_STATE_RINGING ignored, skipping")
            return
        }

        // Extract subId only from the documented AOSP extra "subscription"
        // (corresponds to AOSP PhoneConstants.SUBSCRIPTION_KEY).
        // If the key is missing, subId defaults to -1, which is handled downstream by standard attribution.
        val subId = intent.getIntExtra(EXTRA_SUBSCRIPTION, -1)

        val data =
            Data
                .Builder()
                .putString(CallEventWorker.EXTRA_STATE, state)
                .putInt(CallEventWorker.EXTRA_SUB_ID, subId)
                .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, SystemClock.elapsedRealtime())
                .build()

        val workRequest =
            OneTimeWorkRequestBuilder<CallEventWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, CALL_EVENT_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

        WorkManager
            .getInstance(appContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest,
            ).result
            .await() // Ensure enqueuing completes before finish()
        AppLogger.d("UsageMonitorReceiver: CallEventWorker enqueued for state=$state, subId=$subId")
    }

    companion object {
        /**
         * Documented AOSP extra key for subscription ID in [TelephonyManager.ACTION_PHONE_STATE_CHANGED] broadcasts.
         * Corresponds to AOSP `PhoneConstants.SUBSCRIPTION_KEY`.
         */
        const val EXTRA_SUBSCRIPTION = "subscription"

        private const val UNIQUE_WORK_NAME = "CALL_EVENT_CHAIN"
        private const val CALL_EVENT_BACKOFF_SECONDS = 30L
    }
}
