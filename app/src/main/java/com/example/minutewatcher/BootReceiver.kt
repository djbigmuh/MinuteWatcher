package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.example.minutewatcher.core.data.InitialSetupWorker
import com.example.minutewatcher.core.data.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                val workRequest = OneTimeWorkRequestBuilder<InitialSetupWorker>().build()
                WorkManager.getInstance(context.applicationContext).enqueue(workRequest).await()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
