package com.example.minutewatcher.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.data.BenchmarkPopulateWorker

/**
 * Receiver to populate mock data for benchmarking.
 * Usage: adb shell am broadcast -a com.example.minutewatcher.benchmark.POPULATE_DATA --ei sim_count 2
 */
class BenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ACTION_POPULATE_DATA) {
            val simCount = intent.getIntExtra(EXTRA_SIM_COUNT, 1)

            val workRequest =
                OneTimeWorkRequestBuilder<BenchmarkPopulateWorker>()
                    .setInputData(BenchmarkPopulateWorker.createInputData(simCount))
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
        }
    }

    companion object {
        const val ACTION_POPULATE_DATA = "com.example.minutewatcher.benchmark.POPULATE_DATA"
        const val EXTRA_SIM_COUNT = "sim_count"
        private const val WORK_NAME = "benchmark_populate_data"
    }
}
