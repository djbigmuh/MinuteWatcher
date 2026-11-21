package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.djbigmuh.minutewatcher.core.domain.BenchmarkUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkPopulateWorkerTest {
    private val benchmarkUseCase: BenchmarkUseCase = mock()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun createWorker(inputData: Data = Data.EMPTY): BenchmarkPopulateWorker =
        TestListenableWorkerBuilder<BenchmarkPopulateWorker>(context, inputData)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        BenchmarkPopulateWorker(
                            appContext,
                            workerParameters,
                            benchmarkUseCase,
                            testDispatcher,
                        )
                },
            ).build()

    @Test
    fun `doWork populates mock data successfully when allowed`() =
        runTest(testDispatcher) {
            val worker = createWorker(BenchmarkPopulateWorker.createInputData(2))
            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            verify(benchmarkUseCase).populateMockData(2)
        }

    @Test
    fun `doWork handles exception and returns failure`() =
        runTest(testDispatcher) {
            whenever(benchmarkUseCase.populateMockData(any())).thenThrow(RuntimeException("Population failed"))
            val worker = createWorker(BenchmarkPopulateWorker.createInputData(1))
            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.failure(), result)
        }

    @Test
    fun `doWork propagates cancellation when stopped`() =
        runTest(testDispatcher) {
            whenever(benchmarkUseCase.populateMockData(any())).thenThrow(CancellationException("Cancelled"))
            val worker = createWorker(BenchmarkPopulateWorker.createInputData(1))
            val result =
                runCatching {
                    worker.doWork()
                }
            assertTrue(result.exceptionOrNull() is CancellationException)
        }
}
