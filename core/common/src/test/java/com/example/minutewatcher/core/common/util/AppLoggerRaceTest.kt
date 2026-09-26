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

    private fun waitForLogMarker(
        marker: String? = null,
        timeoutMs: Long = 10000L,
    ) {
        if (marker != null) {
            AppLogger.d(marker)
        }
        pollUntil(timeoutMs, "Pending logs channel drain") {
            !AppLogger.hasPendingLogsForTest()
        }
        if (marker == null) return

        val logFile = File(logDir, "logs.txt")
        pollUntil(timeoutMs, "Log marker '$marker' in logs.txt") {
            logFile.exists() && runCatching { logFile.readText() }.getOrDefault("").contains(marker)
        }
    }

    private fun pollUntil(
        timeoutMs: Long = 10000L,
        message: String,
        condition: () -> Boolean,
    ) {
        val maxIterations = (timeoutMs / 10).toInt().coerceAtLeast(1)
        repeat(maxIterations) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Timeout waiting for condition: $message", condition())
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

        val raceCompletionMarker = "RACE_TEST_COMPLETED_MARKER"
        waitForLogMarker(raceCompletionMarker)

        val logFile = File(logDir, "logs.txt")
        assertTrue("Log file should exist", logFile.exists())

        val content = logFile.readText()
        assertTrue("Should contain logs from different threads", content.contains("Thread 0 log 0"))
        assertTrue("Should contain logs from different threads", content.contains("Thread 19 log 99"))
    }
}
