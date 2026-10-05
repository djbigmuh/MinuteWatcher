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
        override suspend fun doWork(): Result =
            try {
                val state = inputData.getString(EXTRA_STATE)
                val subId = inputData.getInt(EXTRA_SUB_ID, -1)
                val eventElapsedRealtime = inputData.getLong(EXTRA_EVENT_ELAPSED_REALTIME, -1L)

                when (state) {
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> callTracker.onCallStarted(subId, eventElapsedRealtime)
                    TelephonyManager.EXTRA_STATE_IDLE -> callTracker.onCallEnded(subId, eventElapsedRealtime)
                    TelephonyManager.EXTRA_STATE_RINGING -> AppLogger.d("CallEventWorker: ringing state ignored")
                    else -> AppLogger.w("CallEventWorker: unknown state=$state")
                }

                Result.success()
            } catch (e: SecurityException) {
                if (runAttemptCount < MAX_EVENT_RUN_ATTEMPTS) {
                    AppLogger.w(
                        "CallEventWorker: Permission error, retrying. Attempt: $runAttemptCount",
                        e,
                    )
                    Result.retry()
                } else {
                    AppLogger.e(
                        "CallEventWorker: Permission error, max attempts reached ($runAttemptCount)",
                        e,
                    )
                    Result.failure()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppLogger.w("CallEventWorker: IO error, retrying. Attempt: $runAttemptCount. Message: ${e.message}")
                Result.retry()
            } catch (e: Exception) {
                if (runAttemptCount < MAX_EVENT_RUN_ATTEMPTS) {
                    AppLogger.w(
                        "CallEventWorker failed with unexpected error, retrying. Attempt: $runAttemptCount",
                        e,
                    )
                    Result.retry()
                } else {
                    AppLogger.e(
                        "CallEventWorker failed with unexpected error, max attempts reached ($runAttemptCount)",
                        e,
                    )
                    Result.failure()
                }
            }

        companion object {
            const val EXTRA_STATE = "state"
            const val EXTRA_SUB_ID = "subId"
            const val EXTRA_EVENT_ELAPSED_REALTIME = "eventElapsedRealtime"

            /**
             * Maximum number of retry attempts for SecurityException and unexpected errors
             * before considering work failed.
             *
             * IOException is retried unconditionally without attempt limit because storage degradation
             * is considered transient. Call events are processed in a unique WorkManager chain with timestamps
             * captured in input data, ensuring call durations remain accurate once storage recovers.
             * Retry frequency is constrained by WorkManager's exponential backoff.
             */
            const val MAX_EVENT_RUN_ATTEMPTS = 3
        }
    }
