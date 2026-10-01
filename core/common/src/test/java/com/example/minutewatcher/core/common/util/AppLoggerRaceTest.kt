package com.example.minutewatcher.core.common.util

import com.example.minutewatcher.core.common.testing.AppLoggerResetRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
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
                assertTrue("Timed out waiting for release latch", latch.await(10, TimeUnit.SECONDS))
                repeat(logsPerThread) { logIdx ->
                    AppLogger.d("Thread $threadIdx log $logIdx")
                }
                completedLatch.countDown()
            }.start()
        }

        assertTrue("Timed out waiting for start latch", startLatch.await(10, TimeUnit.SECONDS))
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

    @Test
    fun `concurrent logging with lifecycle resets does not throw uncaught exceptions and keeps file logging active`() {
        val threadCount = 10
        val logsPerThread = 200
        val lifecycleIterations = 20

        val startLatch = CountDownLatch(threadCount + 1)
        val readyLatch = CountDownLatch(1)
        val completedLatch = CountDownLatch(threadCount + 1)

        val exceptions = ConcurrentLinkedQueue<Throwable>()

        repeat(threadCount) { threadIdx ->
            Thread {
                try {
                    startLatch.countDown()
                    assertTrue("Timed out waiting for ready latch", readyLatch.await(10, TimeUnit.SECONDS))
                    repeat(logsPerThread) { logIdx ->
                        when (logIdx % 3) {
                            0 -> AppLogger.d("Thread $threadIdx debug $logIdx")
                            1 -> AppLogger.w("Thread $threadIdx warn $logIdx")
                            else -> AppLogger.e("Thread $threadIdx error $logIdx")
                        }
                    }
                } catch (t: Throwable) {
                    exceptions.add(t)
                } finally {
                    completedLatch.countDown()
                }
            }.start()
        }

        Thread {
            try {
                startLatch.countDown()
                assertTrue("Timed out waiting for ready latch", readyLatch.await(10, TimeUnit.SECONDS))
                repeat(lifecycleIterations) { iter ->
                    if (iter % 2 == 0) {
                        AppLogger.initForTest(logDir, enabled = true)
                    } else {
                        AppLogger.resetForTest()
                    }
                    Thread.sleep(2)
                }
                AppLogger.initForTest(logDir, enabled = true)
            } catch (t: Throwable) {
                exceptions.add(t)
            } finally {
                completedLatch.countDown()
            }
        }.start()

        assertTrue("Timed out waiting for start latch", startLatch.await(10, TimeUnit.SECONDS))
        readyLatch.countDown()

        assertTrue("All threads should finish within timeout", completedLatch.await(15, TimeUnit.SECONDS))
        assertTrue("No uncaught exceptions should occur: $exceptions", exceptions.isEmpty())

        AppLogger.initForTest(logDir, enabled = true)
        val finalMarker = "CONCURRENT_TEST_FINAL_MARKER"
        waitForLogMarker(finalMarker)

        val logFile = File(logDir, "logs.txt")
        assertTrue("Log file should exist", logFile.exists())
        assertTrue("Log file should contain final marker", logFile.readText().contains(finalMarker))
    }
}
