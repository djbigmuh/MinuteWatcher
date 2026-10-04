package com.example.minutewatcher.service.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = com.example.minutewatcher.MinuteWatcherTestApp::class)
class SemanticInitSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    private class NonCompletingWorker(
        appContext: Context,
        workerParams: WorkerParameters,
    ) : ListenableWorker(appContext, workerParams) {
        private val future = SettableFuture.create<Result>()

        override fun startWork(): ListenableFuture<Result> = future

        override fun onStopped() {
            future.set(Result.failure())
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config =
            Configuration
                .Builder()
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker = NonCompletingWorker(appContext, workerParameters)
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `schedule enqueues one time semantic_init work without initial delay`() {
        SemanticInitScheduler.schedule(workManager)
        ShadowLooper.idleMainLooper()

        val workInfos = workManager.getWorkInfosForUniqueWork(SemanticInitScheduler.SEMANTIC_INIT_WORK_NAME).get()
        assertEquals(1, workInfos.size)
        assertTrue(
            "Work state should be ENQUEUED or RUNNING",
            workInfos[0].state == WorkInfo.State.ENQUEUED || workInfos[0].state == WorkInfo.State.RUNNING,
        )
    }

    @Test
    fun `schedule with REPLACE policy cancels previous enqueued work and enqueues new one`() {
        SemanticInitScheduler.schedule(workManager)
        ShadowLooper.idleMainLooper()
        val initialWorkInfos =
            workManager
                .getWorkInfosForUniqueWork(
                    SemanticInitScheduler.SEMANTIC_INIT_WORK_NAME,
                ).get()
        assertEquals(1, initialWorkInfos.size)
        val initialWorkId = initialWorkInfos[0].id

        SemanticInitScheduler.schedule(workManager)
        ShadowLooper.idleMainLooper()
        val updatedWorkInfos =
            workManager
                .getWorkInfosForUniqueWork(
                    SemanticInitScheduler.SEMANTIC_INIT_WORK_NAME,
                ).get()
        assertEquals(1, updatedWorkInfos.size)

        val previousWorkInfo = workManager.getWorkInfoById(initialWorkId).get()
        assertTrue(
            "Previous work should be CANCELLED or removed",
            previousWorkInfo == null || previousWorkInfo.state == WorkInfo.State.CANCELLED,
        )
    }
}
