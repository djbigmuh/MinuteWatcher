package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for scheduling background work using [WorkManager].
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class WorkScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * Schedules one-time initial setup work.
         *
         * @param force If true, uses [ExistingWorkPolicy.REPLACE] to ensure fresh initialization.
         *              Otherwise, uses [ExistingWorkPolicy.KEEP].
         * @param triggeredByBoot If true, indicates that the work was triggered by a system boot event.
         * @return [Operation] that can be used to track the progress of the enqueuing operation.
         * Safe to call from any coroutine context.
         */
        fun scheduleInitialSetup(
            force: Boolean = false,
            triggeredByBoot: Boolean = false,
        ): Operation {
            val inputData =
                Data
                    .Builder()
                    .putBoolean(InitialSetupWorker.EXTRA_TRIGGERED_BY_BOOT, triggeredByBoot)
                    .build()

            val workRequest =
                OneTimeWorkRequestBuilder<InitialSetupWorker>()
                    .setInputData(inputData)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_SECONDS,
                        TimeUnit.SECONDS,
                    ).build()

            val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

            // By default, we use KEEP to avoid cancelling and restarting migrations/setup
            // on every activity recreation (e.g., screen rotation).
            return WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_SETUP_WORK_NAME,
                policy,
                workRequest,
            )
        }

        /**
         * Schedules periodic usage check work (hourly).
         *
         * Requires non-low battery constraint.
         * @return [Operation] that can be used to track the progress of the enqueuing operation.
         * Safe to call from any coroutine context.
         */
        fun schedulePeriodicUsageCheck(): Operation {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(requiresBatteryNotLow = true)
                    .build()

            val workRequest =
                PeriodicWorkRequestBuilder<UsageCheckWorker>(1, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_SECONDS,
                        TimeUnit.SECONDS,
                    ).build()

            return WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                USAGE_CHECK_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest,
            )
        }

        companion object {
            const val INITIAL_SETUP_WORK_NAME = "INITIAL_SETUP"
            const val USAGE_CHECK_WORK_NAME = "UsageCheckWork"
            private const val BACKOFF_SECONDS = 30L
        }
    }
