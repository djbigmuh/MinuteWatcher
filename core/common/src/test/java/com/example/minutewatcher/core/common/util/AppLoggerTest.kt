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

        // Write ~3.0 MB (3000 * 1KB) to trigger rotation (MAX_FILE_SIZE = 1MB)
        // We use more than 2MB to ensure rotation happens even with minimal overhead
        repeat(3000) {
            AppLogger.d(largeMessage)
        }

        val rotationMarker = "ROTATION_MARKER_TEST_1"
        waitForLogMarker(rotationMarker)

        val logFile = File(logDir, "logs.txt")
        val oldFile = File(logDir, "logs.old.txt")

        assertTrue("logs.old.txt should exist after rotation", oldFile.exists())
        assertTrue("logs.txt should exist after rotation", logFile.exists())

        // After rotation, logs.txt should contain only the entries that overflowed
        assertTrue("logs.txt should be smaller than logs.old.txt", logFile.length() < oldFile.length())

        val uniqueTag = "UNIQUE_LOG_TAG_12345"
        waitForLogMarker(uniqueTag)

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
        waitForLogMarker(marker)

        val logFile = File(logDir, "logs.txt")
        assertTrue(logFile.readText().contains(marker))

        AppLogger.shutdown()

        val markerAfter = "LOG_AFTER_SHUTDOWN"
        AppLogger.d(markerAfter)
        waitForLogMarker(marker = null)

        assertFalse("Should not contain log after shutdown", logFile.readText().contains(markerAfter))
    }

    private fun waitForLogMarker(
        marker: String? = null,
        timeoutMs: Long = 10000L,
    ) {
        if (marker != null) {
            AppLogger.d(marker)
        }
        val start = System.currentTimeMillis()
        while (AppLogger.hasPendingLogsForTest() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(20)
        }
        if (marker == null) return

        val logFile = File(logDir, "logs.txt")
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (logFile.exists()) {
                val content = runCatching { logFile.readText() }.getOrDefault("")
                if (content.contains(marker)) {
                    return
                }
            }
            Thread.sleep(20)
        }
    }
}
