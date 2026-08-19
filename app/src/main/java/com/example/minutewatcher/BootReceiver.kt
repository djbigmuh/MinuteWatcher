package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.InitialSetupWorker
import com.example.minutewatcher.core.data.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // goAsync() is REQUIRED: WorkManager.enqueue() is asynchronous and may outlive
        // the BroadcastReceiver's process lifetime. Without goAsync(), the system kills
        // the process before enqueuing completes, silently dropping the task.
        // This is strict Android lifecycle compliance, not an optimization.
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Main) {
            try {
                val workRequest =
                    OneTimeWorkRequestBuilder<InitialSetupWorker>()
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            BACKOFF_DELAY_SECONDS,
                            TimeUnit.SECONDS,
                        ).build()
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    WorkScheduler.INITIAL_SETUP_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest,
                ).result.await() // Ensure enqueuing completes before finish()
                AppLogger.d("BootReceiver: InitialSetup scheduled successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("BootReceiver: Failed to schedule InitialSetup", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        private const val BACKOFF_DELAY_SECONDS = 30L
    }
}
