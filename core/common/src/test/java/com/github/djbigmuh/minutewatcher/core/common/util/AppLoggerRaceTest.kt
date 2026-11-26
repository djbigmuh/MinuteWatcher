package com.github.djbigmuh.minutewatcher.core.common.util

import com.github.djbigmuh.minutewatcher.core.common.testing.AppLoggerResetRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

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
        AppLogger.initForTest(logDir)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    private suspend fun waitForLogMarker(
        marker: String? = null,
        timeoutMs: Long = 10000L,
    ) {
        if (marker != null) {
            AppLogger.d(marker)
            AppLogger.awaitQuiescent(timeoutMs)
            val logFile = File(logDir, "logs.txt")
            pollUntil(timeoutMs, "Log marker '$marker' in logs.txt") {
                logFile.exists() && runCatching { logFile.readText() }.getOrDefault("").contains(marker)
            }
        }
    }

    @Test
    fun `concurrent logging from multiple threads is safe`() =
        runTest {
            val threadCount = 20
            val logsPerThread = 100

            val jobs =
                List(threadCount) { threadIdx ->
                    launch(Dispatchers.Default) {
                        repeat(logsPerThread) { logIdx ->
                            AppLogger.d("Thread $threadIdx log $logIdx")
                        }
                    }
                }
            jobs.joinAll()

            val raceCompletionMarker = "RACE_TEST_COMPLETED_MARKER"
            waitForLogMarker(raceCompletionMarker)

            val logFile = File(logDir, "logs.txt")
            assertTrue("Log file should exist", logFile.exists())

            val content = logFile.readText()
            assertTrue("Should contain logs from different threads", content.contains("Thread 0 log 0"))
            assertTrue("Should contain logs from different threads", content.contains("Thread 19 log 99"))
        }

    @Test
    fun `concurrent logging with lifecycle resets does not throw uncaught exceptions and keeps file logging active`() =
        runTest {
            val threadCount = 10
            val logsPerThread = 200
            val lifecycleIterations = 20

            val workerJobs =
                List(threadCount) { threadIdx ->
                    launch(Dispatchers.Default) {
                        repeat(logsPerThread) { logIdx ->
                            val msg = "Thread $threadIdx log $logIdx"
                            when (logIdx % 3) {
                                0 -> AppLogger.d(msg)
                                1 -> AppLogger.w(msg)
                                else -> AppLogger.e(msg)
                            }
                        }
                    }
                }

            val lifecycleJob =
                launch(Dispatchers.Default) {
                    repeat(lifecycleIterations) { iter ->
                        if (iter % 2 == 0) AppLogger.initForTest(logDir) else AppLogger.resetForTest()
                        delay(2.milliseconds)
                    }
                    AppLogger.initForTest(logDir)
                }

            (workerJobs + lifecycleJob).joinAll()

            AppLogger.initForTest(logDir)
            val finalMarker = "CONCURRENT_TEST_FINAL_MARKER"
            waitForLogMarker(finalMarker)

            val logFile = File(logDir, "logs.txt")
            assertTrue("Log file should exist", logFile.exists())
            assertTrue("Log file should contain final marker", logFile.readText().contains(finalMarker))
        }
}
