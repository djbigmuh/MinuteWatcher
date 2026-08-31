package com.example.minutewatcher.core.common.util

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
        // Each entry line is approximately:
        // "yyyy-MM-dd HH:mm:ss.SSS D/MinuteWatcher: message\n"
        // ~30 chars timestamp + 20 chars level/tag + message length
        val messageSize = 1024
        val largeMessage = "A".repeat(messageSize)

        // Write ~1.1 MB (1100 * 1KB)
        repeat(1100) {
            AppLogger.d(largeMessage)
        }

        // Wait for worker to catch up
        waitForWorker()

        val logFile = File(logDir, "logs.txt")
        val oldFile = File(logDir, "logs.old.txt")

        assertTrue("logs.old.txt should exist after rotation", oldFile.exists())
        assertTrue("logs.txt should exist after rotation", logFile.exists())

        // After rotation, logs.txt should be small (contains only the entries that overflowed)
        assertTrue("logs.txt should be smaller than logs.old.txt", logFile.length() < oldFile.length())

        // Verify that a NEW entry goes into logs.txt and NOT into logs.old.txt
        val uniqueTag = "UNIQUE_LOG_TAG_12345"
        AppLogger.d(uniqueTag)
        waitForWorker()

        val newContent = logFile.readText()
        assertTrue("New logs should be in logs.txt", newContent.contains(uniqueTag))

        val oldContent = oldFile.readText()
        assertFalse("New logs should NOT be in logs.old.txt", oldContent.contains(uniqueTag))
    }

    @Test
    fun `test critical log not lost under contention`() {
        // 1. Fill the queue to trigger fallback
        // Capacity is 8192.
        repeat(8192) {
            AppLogger.e("Filling queue $it")
        }

        // 2. Manually hold the file lock via reflection to simulate contention
        val fileLockField = AppLogger::class.java.getDeclaredField("fileLock")
        fileLockField.isAccessible = true
        val fileLock = fileLockField.get(null) as java.util.concurrent.locks.ReentrantLock

        fileLock.lock()
        try {
            // 3. Log a critical message while lock is held and queue is full
            val uniqueMessage = "CRITICAL_MESSAGE_UNDER_CONTENTION"
            AppLogger.e(uniqueMessage)

            // Since queue was full, it went to handleSynchronousFallback.
            // It tried to lock for 500ms, failed, then tried to re-queue.
            // Since the worker is likely processing, space might become available or not.

            // 4. Release lock and wait for worker
        } finally {
            fileLock.unlock()
        }

        waitForWorker()

        val logFile = File(logDir, "logs.txt")
        if (logFile.exists()) {
            val content = logFile.readText()
            assertTrue(
                "Critical log should be preserved in file",
                content.contains("CRITICAL_MESSAGE_UNDER_CONTENTION"),
            )
        }
    }

    private fun waitForWorker() {
        var attempts = 0
        while (AppLogger.getQueueSizeForTest() > 0 && attempts < 100) {
            Thread.sleep(100)
            attempts++
        }
        // Give the worker a tiny bit more time to finish the last writeEntryToFile
        Thread.sleep(500)
    }
}
