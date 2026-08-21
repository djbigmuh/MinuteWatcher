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
         * Uses [ExistingWorkPolicy.REPLACE] to ensure fresh initialization.
         * Safe to call from any coroutine context.
         */
        fun scheduleInitialSetup() {
            val workRequest =
                OneTimeWorkRequestBuilder<InitialSetupWorker>()
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_SECONDS,
                        TimeUnit.SECONDS,
                    ).build()
            // REPLACE policy is used to ensure that the setup logic runs immediately
            // with current permissions and system state, overriding any stale or
            // pending initialization tasks from previous app launches.
            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_SETUP_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
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
                    .setRequiresBatteryNotLow(true)
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
