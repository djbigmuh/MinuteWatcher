package com.example.minutewatcher.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.BenchmarkUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Worker to populate mock data for benchmarking purposes.
 * This ensures that heavy data operations are performed in the background.
 */
@HiltWorker
class BenchmarkPopulateWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val benchmarkUseCase: BenchmarkUseCase,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            withContext(ioDispatcher) {
                if (!com.example.minutewatcher.core.common.BuildConfig.DEBUG) {
                    AppLogger.w("BenchmarkPopulateWorker blocked: Not in DEBUG mode")
                    return@withContext Result.failure()
                }

                try {
                    val simCount = inputData.getInt(EXTRA_SIM_COUNT, 1)
                    AppLogger.d("BenchmarkPopulateWorker: Starting population for $simCount SIMs")
                    benchmarkUseCase.populateMockData(simCount)
                    Result.success()
                } catch (e: Exception) {
                    AppLogger.e("BenchmarkPopulateWorker failed to populate mock data", e)
                    Result.failure()
                }
            }

        companion object {
            const val EXTRA_SIM_COUNT = "sim_count"

            /**
             * Creates input data for the worker.
             * @param simCount Number of SIMs to populate (1 or 2).
             */
            fun createInputData(simCount: Int): Data =
                Data
                    .Builder()
                    .putInt(EXTRA_SIM_COUNT, simCount)
                    .build()
        }
    }
