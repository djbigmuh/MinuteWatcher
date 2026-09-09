package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@AndroidEntryPoint
open class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var workScheduler: WorkScheduler

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = acquirePendingResult()
        goAsyncWithScope(context, pendingResult) { _ ->
            try {
                // Force REPLACE on boot to ensure fresh initialization after restart.
                workScheduler.scheduleInitialSetup(force = true)
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
}
