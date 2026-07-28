package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.data.InitialSetupWorker
import com.example.minutewatcher.core.data.WorkScheduler
import com.example.minutewatcher.core.data.di.IoDispatcher
import com.example.minutewatcher.core.model.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            try {
                val workRequest =
                    OneTimeWorkRequestBuilder<InitialSetupWorker>()
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            30,
                            TimeUnit.SECONDS,
                        )
                        .build()
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    WorkScheduler.INITIAL_SETUP_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest,
                )
                AppLogger.d("BootReceiver: InitialSetup scheduled successfully")
            } catch (e: Exception) {
                AppLogger.e("BootReceiver: Failed to schedule InitialSetup", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
