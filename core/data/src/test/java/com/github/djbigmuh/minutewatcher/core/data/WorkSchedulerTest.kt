package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var workScheduler: WorkScheduler
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val testWorkerFactory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    object : ListenableWorker(appContext, workerParameters) {
                        override fun startWork(): ListenableFuture<Result> = SettableFuture.create()
                    }
            }
        val config =
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(testWorkerFactory)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        workScheduler = WorkScheduler(context)
    }

    @Test
    fun `scheduleInitialSetup with force false enqueues work with keep policy`() {
        workScheduler.scheduleInitialSetup(force = false).result.get()

        val workInfos = workManager.getWorkInfosForUniqueWork(WorkScheduler.INITIAL_SETUP_WORK_NAME).get()
        assertEquals(1, workInfos.size)

        val firstWorkId = workInfos[0].id

        // Re-call with force = false -> KEEP policy should preserve the same work ID
        workScheduler.scheduleInitialSetup(force = false).result.get()

        val updatedWorkInfos = workManager.getWorkInfosForUniqueWork(WorkScheduler.INITIAL_SETUP_WORK_NAME).get()
        val activeWork = updatedWorkInfos.first { it.state != WorkInfo.State.CANCELLED }
        assertEquals(firstWorkId, activeWork.id)
    }

    @Test
    fun `scheduleInitialSetup with force true replaces work with replace policy`() {
        workScheduler.scheduleInitialSetup(force = false).result.get()

        val firstWorkInfos = workManager.getWorkInfosForUniqueWork(WorkScheduler.INITIAL_SETUP_WORK_NAME).get()
        val firstWorkId = firstWorkInfos[0].id

        // Call with force = true -> REPLACE policy should cancel previous work and enqueue a new work ID
        workScheduler.scheduleInitialSetup(force = true).result.get()

        val newWorkInfos = workManager.getWorkInfosForUniqueWork(WorkScheduler.INITIAL_SETUP_WORK_NAME).get()
        val activeWork = newWorkInfos.first { it.id != firstWorkId }
        assertNotEquals(firstWorkId, activeWork.id)

        val canceledWork = newWorkInfos.firstOrNull { it.id == firstWorkId }
        if (canceledWork != null) {
            assertEquals(WorkInfo.State.CANCELLED, canceledWork.state)
        }
    }

    @Test
    fun `schedulePeriodicUsageCheck enqueues periodic work`() {
        workScheduler.schedulePeriodicUsageCheck().result.get()

        val workInfos = workManager.getWorkInfosForUniqueWork(WorkScheduler.USAGE_CHECK_WORK_NAME).get()
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos[0].state)
    }

    @Test
    fun `schedulePeriodicUsageCheck keeps existing work with keep policy`() {
        workScheduler.schedulePeriodicUsageCheck().result.get()

        val workInfos = workManager.getWorkInfosForUniqueWork(WorkScheduler.USAGE_CHECK_WORK_NAME).get()
        assertEquals(1, workInfos.size)
        val firstWorkId = workInfos[0].id

        workScheduler.schedulePeriodicUsageCheck().result.get()

        val updatedWorkInfos = workManager.getWorkInfosForUniqueWork(WorkScheduler.USAGE_CHECK_WORK_NAME).get()
        val activeWorks = updatedWorkInfos.filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, activeWorks.size)
        assertEquals(firstWorkId, activeWorks[0].id)
    }
}
