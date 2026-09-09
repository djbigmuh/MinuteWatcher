package com.example.minutewatcher.core.common.util

import com.example.minutewatcher.core.common.testing.AppLoggerResetRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppLoggerTest {
    @get:Rule
    val loggerResetRule = AppLoggerResetRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var logDir: File

    @Before
    fun setup() {
        logDir = tmpFolder.newFolder("logs")
        AppLogger.initForTest(logDir, enabled = true)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    @Test
    fun `test log rotation occurs and new entries go to new file`() {
        val messageSize = 1024
        val largeMessage = "A".repeat(messageSize)

        // Write ~2.0 MB (2000 * 1KB) to trigger rotation (MAX_FILE_SIZE = 1MB)
        repeat(2000) {
            AppLogger.d(largeMessage)
        }

        // Wait for worker to finish processing the channel
        waitForWorker()

        val logFile = File(logDir, "logs.txt")
        val oldFile = File(logDir, "logs.old.txt")

        assertTrue("logs.old.txt should exist after rotation", oldFile.exists())
        assertTrue("logs.txt should exist after rotation", logFile.exists())

        // After rotation, logs.txt should contain only the entries that overflowed
        assertTrue("logs.txt should be smaller than logs.old.txt", logFile.length() < oldFile.length())

        val uniqueTag = "UNIQUE_LOG_TAG_12345"
        AppLogger.d(uniqueTag)
        waitForWorker()

        assertTrue("New logs should be in logs.txt", logFile.readText().contains(uniqueTag))
        assertFalse("New logs should NOT be in logs.old.txt", oldFile.readText().contains(uniqueTag))
    }

    @Test
    fun `public methods do not throw when logger or dir is missing`() {
        AppLogger.resetForTest() // Ensure not initialized

        AppLogger.d("test")
        AppLogger.w("test", RuntimeException())
        AppLogger.e("test", RuntimeException())

        // Should not crash
    }

    @Test
    fun `shutdown prevents file logging`() {
        val marker = "LOG_BEFORE_SHUTDOWN"
        AppLogger.d(marker)
        waitForWorker()

        val logFile = File(logDir, "logs.txt")
        assertTrue(logFile.readText().contains(marker))

        AppLogger.shutdown()

        val markerAfter = "LOG_AFTER_SHUTDOWN"
        AppLogger.d(markerAfter)
        waitForWorker()

        assertFalse("Should not contain log after shutdown", logFile.readText().contains(markerAfter))
    }

    private fun waitForWorker() {
        // Since we use CoroutineScope and Dispatchers.IO, we just wait long enough
        // in Robolectric environment where background work is usually synchronous or fast.
        Thread.sleep(1000)
    }
}
