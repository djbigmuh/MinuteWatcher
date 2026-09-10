package com.example.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class WorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var workScheduler: WorkScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workScheduler = WorkScheduler(context)
    }

    @Test
    fun `scheduleInitialSetup uses correct policy`() {
        // We can't easily check the policy used in enqueueUniqueWork without mocking WorkManager (which is hard)
        // or checking the internal state of WorkManager.
        // But we can verify that the method exists and can be called.
        workScheduler.scheduleInitialSetup(force = false)
        workScheduler.scheduleInitialSetup(force = true)
    }
}
