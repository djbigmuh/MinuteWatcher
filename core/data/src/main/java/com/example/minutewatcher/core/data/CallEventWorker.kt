package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class CallEventWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val callTracker: CallTracker,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            val state = inputData.getString("state")
            val subId = inputData.getInt("subId", -1)

            when (state) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> callTracker.onCallStarted(subId)
                TelephonyManager.EXTRA_STATE_IDLE -> callTracker.onCallEnded(subId)
            }

            return Result.success()
        }
    }
