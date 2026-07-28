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

@Singleton
class WorkScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun scheduleInitialSetup() {
            val workRequest =
                OneTimeWorkRequestBuilder<InitialSetupWorker>()
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS,
                    )
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_SETUP_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
        }

        fun schedulePeriodicUsageCheck() {
            val constraints =
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()

            val workRequest =
                PeriodicWorkRequestBuilder<UsageCheckWorker>(1, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS,
                    )
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                USAGE_CHECK_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest,
            )
        }

        companion object {
            const val INITIAL_SETUP_WORK_NAME = "INITIAL_SETUP"
            const val USAGE_CHECK_WORK_NAME = "UsageCheckWork"
        }
    }
