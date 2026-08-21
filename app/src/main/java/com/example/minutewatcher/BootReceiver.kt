package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.InitialSetupWorker
import com.example.minutewatcher.core.data.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // goAsync() extends the receiver's lifetime until finish() is called.
        // Required because WorkManager.enqueue() may block on disk I/O.
        // Without this, the system may kill the receiver process before
        // the work is actually enqueued.
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        // Строгое соблюдение API: BroadcastReceiver с goAsync() должен вызвать
        // PendingResult.finish() до завершения. SupervisorJob гарантирует, что
        // отмена одной корутины не повлияет на другие. finish() вызывается в
        // finally, поэтому даже при отмене корутины он будет вызван.
        // На AOSP BroadcastReceiver с goAsync() живёт до вызова finish().
        // Если кастомная прошивка убивает процесс до вызова finish() —
        // это проблема кастомной прошивки.
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
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
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val BACKOFF_DELAY_SECONDS = 30L
    }
}
