package com.example.minutewatcher.core.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
         * Safe to call from any coroutine context.
         */
        fun scheduleInitialSetup(force: Boolean = false) {
            val workRequest =
                OneTimeWorkRequestBuilder<InitialSetupWorker>()
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_SECONDS,
                        TimeUnit.SECONDS,
                    ).build()

            val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

            // By default, we use KEEP to avoid cancelling and restarting migrations/setup
            // on every activity recreation (e.g., screen rotation).
            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_SETUP_WORK_NAME,
                policy,
                workRequest,
            )
        }

        /**
         * Schedules periodic usage check work (hourly).
         *
         * Requires non-low battery constraint.
         * Safe to call from any coroutine context.
         */
        fun schedulePeriodicUsageCheck() {
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

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
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
