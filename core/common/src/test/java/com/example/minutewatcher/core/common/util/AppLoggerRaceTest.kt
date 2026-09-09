package com.example.minutewatcher.core.common.util

import com.example.minutewatcher.core.common.testing.AppLoggerResetRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppLoggerRaceTest {
    @get:Rule
    val loggerResetRule = AppLoggerResetRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var logDir: File

    @Before
    fun setup() {
        logDir = tempFolder.newFolder("logs")
        AppLogger.resetForTest()
        AppLogger.initForTest(logDir, enabled = true)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    @Test
    fun `concurrent logging from multiple threads is safe`() {
        val threadCount = 20
        val logsPerThread = 100
        val latch = CountDownLatch(1)
        val startLatch = CountDownLatch(threadCount)
        val completedLatch = CountDownLatch(threadCount)

        repeat(threadCount) { threadIdx ->
            Thread {
                startLatch.countDown()
                latch.await()
                repeat(logsPerThread) { logIdx ->
                    AppLogger.d("Thread $threadIdx log $logIdx")
                }
                completedLatch.countDown()
            }.start()
        }

        startLatch.await()
        latch.countDown() // Start all threads at once
        assertTrue("Logging should complete", completedLatch.await(10, TimeUnit.SECONDS))

        // Wait for channel to be processed
        Thread.sleep(2000)

        val logFile = File(logDir, "logs.txt")
        assertTrue("Log file should exist", logFile.exists())

        val content = logFile.readText()
        // We might drop some logs if the channel capacity (8192) is exceeded,
        // but 20 * 100 = 2000 which is well within capacity.
        assertTrue("Should contain logs from different threads", content.contains("Thread 0 log 0"))
        assertTrue("Should contain logs from different threads", content.contains("Thread 19 log 99"))
    }
}
