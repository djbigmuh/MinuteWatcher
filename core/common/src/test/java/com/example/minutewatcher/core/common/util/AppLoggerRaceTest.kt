package com.example.minutewatcher.core.common.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppLoggerRaceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        AppLogger.resetForTest()
        AppLogger.initForTest(tempFolder.newFolder(), enabled = true)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    @Test
    fun `ensureWorkerStarted starts only one worker thread even when called concurrently after crash`() {
        // 1. Start initial worker
        AppLogger.d("Initial log to start worker")

        // Wait a bit to ensure it started
        Thread.sleep(200)

        // 2. Simulate worker crash by manually setting workerThread to null
        // Since we now check thread.isAlive, we must clear the reference to simulate a crash/death
        val field = AppLogger::class.java.getDeclaredField("workerThread")
        field.isAccessible = true
        val oldThread = field.get(AppLogger) as? Thread
        field.set(AppLogger, null)

        // Kill the old thread so it doesn't count in our check
        oldThread?.interrupt()
        Thread.sleep(100)

        // 3. Try to start worker from multiple threads simultaneously
        val threadCount = 10
        val latch = CountDownLatch(1)
        val startLatch = CountDownLatch(threadCount)
        val completedLatch = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                startLatch.countDown()
                latch.await()
                // Use reflection to call private ensureWorkerStarted
                val method = AppLogger::class.java.getDeclaredMethod("ensureWorkerStarted")
                method.isAccessible = true
                method.invoke(AppLogger)
                completedLatch.countDown()
            }.start()
        }

        startLatch.await()
        latch.countDown() // Release all threads at once
        completedLatch.await(5, TimeUnit.SECONDS)

        // 4. Count active worker threads
        val activeWorkers = Thread.getAllStackTraces().keys.count { it.name == "AppLogger-Worker" }

        assertEquals("Should only have one active worker thread", 1, activeWorkers)
    }
}
