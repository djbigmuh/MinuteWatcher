package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.InitialSetupWorker
import com.example.minutewatcher.core.data.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import java.util.concurrent.TimeUnit

open class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = acquirePendingResult()
        goAsyncWithScope(context, pendingResult) { appContext ->
            try {
                val workRequest =
                    OneTimeWorkRequestBuilder<InitialSetupWorker>()
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            BACKOFF_DELAY_SECONDS,
                            TimeUnit.SECONDS,
                        ).build()
                WorkManager
                    .getInstance(appContext)
                    .enqueueUniqueWork(
                        WorkScheduler.INITIAL_SETUP_WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        workRequest,
                    ).result
                    .await()
                AppLogger.d("BootReceiver: InitialSetup scheduled successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("BootReceiver: Failed to schedule InitialSetup", e)
            }
        }
    }

    @VisibleForTesting
    protected open fun acquirePendingResult(): BroadcastReceiver.PendingResult = goAsync()

    companion object {
        private const val BACKOFF_DELAY_SECONDS = 30L
    }
}
