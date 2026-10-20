package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@HiltWorker
class CallEventWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val callTracker: CallTracker,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result =
            withTimeoutOrNull(WORKER_TIMEOUT_MS.milliseconds) {
                processCallEvent()
            } ?: handleTotalTimeout()

        private suspend fun processCallEvent(): Result =
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
                if (runAttemptCount < MAX_EVENT_RUN_ATTEMPTS) {
                    AppLogger.w("CallEventWorker: IO error, retrying. Attempt: $runAttemptCount. Message: ${e.message}")
                    Result.retry()
                } else {
                    AppLogger.e("CallEventWorker: IO error, max attempts reached ($runAttemptCount)", e)
                    Result.failure()
                }
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

        private fun handleTotalTimeout(): Result {
            AppLogger.e("CallEventWorker: timed out after ${WORKER_TIMEOUT_MS}ms")
            return if (runAttemptCount < MAX_EVENT_RUN_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }

        companion object {
            const val EXTRA_STATE = "state"
            const val EXTRA_SUB_ID = "subId"
            const val EXTRA_EVENT_ELAPSED_REALTIME = "eventElapsedRealtime"

            /**
             * Overall worker timeout. On AOSP a single call event is processed within
             * a few seconds; exceeding the budget indicates storage or telephony
             * degradation and is treated as a retryable error within
             * [MAX_EVENT_RUN_ATTEMPTS].
             */
            private const val WORKER_TIMEOUT_MS = 120_000L

            /**
             * Maximum number of retry attempts for recoverable errors (SecurityException, IOException,
             * and unexpected errors) before work completes with [Result.failure]. New call events re-create
             * the chain thanks to APPEND_OR_REPLACE policy in UsageMonitorReceiver.
             */
            const val MAX_EVENT_RUN_ATTEMPTS = 3
        }
    }
