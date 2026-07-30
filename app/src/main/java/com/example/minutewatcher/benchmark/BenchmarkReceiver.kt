package com.example.minutewatcher.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.domain.BenchmarkUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receiver to populate mock data for benchmarking.
 * Usage: adb shell am broadcast -a com.example.minutewatcher.benchmark.POPULATE_DATA --ei sim_count 2
 */
@AndroidEntryPoint
class BenchmarkReceiver : BroadcastReceiver() {

    @Inject
    lateinit var benchmarkUseCase: BenchmarkUseCase

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_POPULATE_DATA) {
            val simCount = intent.getIntExtra(EXTRA_SIM_COUNT, 1)
            val pendingResult = goAsync()

            applicationScope.launch {
                try {
                    benchmarkUseCase.populateMockData(simCount)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_POPULATE_DATA = "com.example.minutewatcher.benchmark.POPULATE_DATA"
        const val EXTRA_SIM_COUNT = "sim_count"
    }
}
