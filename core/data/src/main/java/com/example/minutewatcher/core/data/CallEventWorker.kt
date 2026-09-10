package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.util.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

@HiltWorker
class CallEventWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val callTracker: CallTracker,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            try {
                val state = inputData.getString("state")
                val subId = inputData.getInt("subId", -1)
                val eventElapsedRealtime = inputData.getLong(EXTRA_EVENT_ELAPSED_REALTIME, -1L)

                when (state) {
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> callTracker.onCallStarted(subId, eventElapsedRealtime)
                    TelephonyManager.EXTRA_STATE_IDLE -> callTracker.onCallEnded(subId, eventElapsedRealtime)
                    else -> AppLogger.w("CallEventWorker: unknown state=$state")
                }

                return Result.success()
            } catch (e: SecurityException) {
                AppLogger.e("CallEventWorker: Permission error", e)
                return Result.failure()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppLogger.w("CallEventWorker: IO error, retrying. Attempt: $runAttemptCount. Message: ${e.message}")
                return Result.retry()
            } catch (e: Exception) {
                AppLogger.e("CallEventWorker failed with unexpected error", e)
                return Result.failure()
            }
        }

        companion object {
            const val EXTRA_EVENT_ELAPSED_REALTIME = "eventElapsedRealtime"
        }
    }
