package com.example.minutewatcher.core.data

import android.content.Context
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
            val workRequest = OneTimeWorkRequestBuilder<InitialSetupWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_SETUP_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
        }

        fun schedulePeriodicUsageCheck() {
            val workRequest =
                PeriodicWorkRequestBuilder<UsageCheckWorker>(1, TimeUnit.HOURS)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                USAGE_CHECK_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest,
            )
        }

        companion object {
            const val INITIAL_SETUP_WORK_NAME = "BOOT_SETUP"
            const val USAGE_CHECK_WORK_NAME = "UsageCheckWork"
        }
    }
