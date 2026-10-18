package host.serv.djbigmuh.minutewatcher.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.data.BenchmarkPopulateWorker
import host.serv.djbigmuh.minutewatcher.goAsyncWithScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.guava.await

/**
 * Receiver to populate mock data for benchmarking.
 * Usage: adb shell am broadcast -a host.serv.djbigmuh.minutewatcher.benchmark.POPULATE_DATA --ei sim_count 2
 */
open class BenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_POPULATE_DATA) return

        val pendingResult = acquirePendingResult()
        goAsyncWithScope(context, pendingResult, dispatcher = asyncDispatcher()) { _ ->
            try {
                val simCount = intent.getIntExtra(EXTRA_SIM_COUNT, 1)

                val workRequest =
                    OneTimeWorkRequestBuilder<BenchmarkPopulateWorker>()
                        .setInputData(BenchmarkPopulateWorker.createInputData(simCount))
                        .build()

                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        workRequest,
                    ).result
                    .await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("BenchmarkReceiver: Failed to enqueue benchmark populate work", e)
            }
        }
    }

    @VisibleForTesting
    protected open fun acquirePendingResult(): PendingResult = goAsync()

    @VisibleForTesting
    protected open fun asyncDispatcher(): CoroutineDispatcher? = null

    companion object {
        const val ACTION_POPULATE_DATA = "host.serv.djbigmuh.minutewatcher.benchmark.POPULATE_DATA"
        const val EXTRA_SIM_COUNT = "sim_count"
        private const val WORK_NAME = "benchmark_populate_data"
    }
}
