package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.data.InitialSetupWorker
import com.example.minutewatcher.core.model.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val workRequest = OneTimeWorkRequestBuilder<InitialSetupWorker>().build()
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    "BOOT_SETUP",
                    ExistingWorkPolicy.REPLACE,
                    workRequest,
                )
                AppLogger.d("BootReceiver: InitialSetupWorker enqueued successfully")
            } catch (e: Exception) {
                AppLogger.e("BootReceiver: Failed to enqueue InitialSetupWorker", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
