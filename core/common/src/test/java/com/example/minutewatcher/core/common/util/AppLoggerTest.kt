package com.example.minutewatcher.core.common.util

import android.content.Context
import com.example.minutewatcher.core.common.testing.AppLoggerResetRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

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
        repeat(3000) {
            AppLogger.d(largeMessage)
        }

        val rotationMarker = "ROTATION_MARKER_TEST_1"
        AppLogger.d(rotationMarker)
        waitForLogMarker(rotationMarker)

        val logFile = File(logDir, "logs.txt")
        val oldFile = File(logDir, "logs.old.txt")

        assertTrue("logs.old.txt should exist after rotation", oldFile.exists())
        assertTrue("logs.txt should exist after rotation", logFile.exists())

        assertTrue("logs.txt should be smaller than logs.old.txt", logFile.length() < oldFile.length())

        val uniqueTag = "UNIQUE_LOG_TAG_12345"
        AppLogger.d(uniqueTag)
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
    }

    @Test
    fun `shutdown prevents file logging`() {
        val marker = "LOG_BEFORE_SHUTDOWN"
        AppLogger.d(marker)
        waitForLogMarker(marker)

        val logFile = File(logDir, "logs.txt")
        assertTrue(logFile.readText().contains(marker))

        AppLogger.shutdown()

        val markerAfter = "LOG_AFTER_SHUTDOWN"
        AppLogger.d(markerAfter)
        waitForChannelDrain()

        assertFalse("Should not contain log after shutdown", logFile.readText().contains(markerAfter))
    }

    @Test
    fun `multiple log entries maintain original order and correct format`() {
        val count = 10
        for (i in 1..count) {
            AppLogger.d("Sequential entry #$i")
        }

        val marker = "Sequential entry #$count"
        waitForLogMarker(marker)

        val logFile = File(logDir, "logs.txt")
        val lines = logFile.readLines().filter { it.contains("Sequential entry #") }
        assertEquals(count, lines.size)

        for (i in 1..count) {
            val line = lines[i - 1]
            assertTrue(
                "Line should contain correct level and message: $line",
                line.contains("D/MinuteWatcher: Sequential entry #$i"),
            )
        }
    }

    @Test
    fun `exceeding MAX_FILE_SIZE triggers rotation and retains old and new logs`() {
        val largeChunk = "X".repeat(200_000)
        repeat(6) { idx ->
            AppLogger.d("Pre-rotation chunk $idx: $largeChunk")
        }

        val postRotationMarker = "POST_ROTATION_ENTRY"
        AppLogger.d(postRotationMarker)
        waitForLogMarker(postRotationMarker)

        val oldFile = File(logDir, "logs.old.txt")
        val newFile = File(logDir, "logs.txt")

        assertTrue("logs.old.txt must exist after rotation", oldFile.exists())
        assertTrue("logs.txt must exist after rotation", newFile.exists())
        assertTrue("logs.old.txt should contain pre-rotation logs", oldFile.readText().contains("Pre-rotation chunk 0"))
        assertTrue("logs.txt should contain post-rotation marker", newFile.readText().contains(postRotationMarker))
    }

    @Test
    fun `consecutive write failures disable file logging and public methods do not throw`() {
        val readOnlyFile = File(logDir, "logs.txt")
        readOnlyFile.createNewFile()
        readOnlyFile.setReadOnly()

        repeat(5) { i ->
            AppLogger.d("Failing entry $i")
        }

        waitForChannelDrain()

        AppLogger.d("After failure entry")
        AppLogger.w("Warning after failure")
        AppLogger.e("Error after failure")
    }

    @Test
    fun `drain routes logs to custom logger according to their level`() {
        val testDir = tmpFolder.newFolder("drain_logs")
        val mockContext =
            mock<Context> {
                on { filesDir } doReturn testDir
            }
        var lockHeldDuringDrain = false
        val capturingLogger =
            object : Logger {
                val dLogs = CopyOnWriteArrayList<String>()
                val wLogs = CopyOnWriteArrayList<String>()
                val eLogs = CopyOnWriteArrayList<String>()

                override fun d(
                    tag: String,
                    message: String,
                ) {
                    if (message.contains("[DRAIN]")) {
                        lockHeldDuringDrain = lockHeldDuringDrain || Thread.holdsLock(AppLogger)
                    }
                    dLogs.add(message)
                }

                override fun w(
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) {
                    if (message.contains("[DRAIN]")) {
                        lockHeldDuringDrain = lockHeldDuringDrain || Thread.holdsLock(AppLogger)
                    }
                    wLogs.add(message)
                }

                override fun e(
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) {
                    if (message.contains("[DRAIN]")) {
                        lockHeldDuringDrain = lockHeldDuringDrain || Thread.holdsLock(AppLogger)
                    }
                    eLogs.add(message)
                }
            }

        AppLogger.init(mockContext, capturingLogger)

        val readOnlyFile = File(testDir, "logs.txt")
        readOnlyFile.createNewFile()
        readOnlyFile.setReadOnly()

        repeat(10) { i ->
            AppLogger.d("Debug drain entry $i")
            AppLogger.w("Warning drain entry $i")
            AppLogger.e("Error drain entry $i")
        }

        waitForChannelDrain()

        val hasDebugDrain = capturingLogger.dLogs.any { it.contains("[DRAIN] D/") }
        val hasWarnDrain = capturingLogger.wLogs.any { it.contains("[DRAIN] W/") }
        val hasErrorDrain = capturingLogger.eLogs.any { it.contains("[DRAIN] E/") }

        assertTrue("Debug drain entry in dLogs", hasDebugDrain)
        assertTrue("Warning drain entry in wLogs", hasWarnDrain)
        assertTrue("Error drain entry in eLogs", hasErrorDrain)
        assertFalse("Drain logging must occur outside AppLogger monitor", lockHeldDuringDrain)
    }

    @Test
    fun `calls after shutdown do not throw and do not create log file`() {
        AppLogger.resetForTest()
        val nonExistentDir = File(tmpFolder.root, "non_existent_shutdown_dir")
        AppLogger.initForTest(nonExistentDir, enabled = true)
        AppLogger.shutdown()

        AppLogger.d("Should be ignored")
        AppLogger.w("Should be ignored")
        AppLogger.e("Should be ignored")

        val logFile = File(nonExistentDir, "logs.txt")
        assertFalse("Log file should not be created after shutdown", logFile.exists())
    }

    @Test
    fun `re-initializing for test with new directory switches target and closes previous writer`() {
        val dir1 = tmpFolder.newFolder("logs1")
        val dir2 = tmpFolder.newFolder("logs2")

        AppLogger.initForTest(dir1, enabled = true)
        AppLogger.d("Log in dir1")
        waitForLogMarker("Log in dir1", targetDir = dir1)

        AppLogger.initForTest(dir2, enabled = true)
        AppLogger.d("Log in dir2")
        waitForLogMarker("Log in dir2", targetDir = dir2)

        assertFalse(File(dir1, "logs.txt").readText().contains("Log in dir2"))
        assertTrue(File(dir2, "logs.txt").readText().contains("Log in dir2"))
    }

    @Test
    fun `rotation failure when directory is read only retains active log file content`() {
        val largeChunk = "X".repeat(200_000)
        repeat(6) { idx ->
            AppLogger.d("Pre-rotation chunk $idx: $largeChunk")
        }

        val preRotationMarker = "PRE_ROTATION_MARKER"
        AppLogger.d(preRotationMarker)
        waitForLogMarker(preRotationMarker)

        val logFile = File(logDir, "logs.txt")
        assertTrue("logs.txt must exist before rotation", logFile.exists())

        try {
            logDir.setReadOnly()

            val triggerMarker = "TRIGGER_ROTATION_ENTRY"
            AppLogger.d(triggerMarker)
            waitForChannelDrain()

            assertTrue("logs.txt must exist after failed rotation", logFile.exists())
            val contentAfter = logFile.readText()
            assertTrue("logs.txt must retain pre-rotation content", contentAfter.contains(preRotationMarker))
        } finally {
            logDir.setWritable(true)
        }
    }

    @Test
    fun `reinitializing for test resets failure counter and reenables file logging`() {
        val readOnlyFile = File(logDir, "logs.txt")
        readOnlyFile.createNewFile()
        readOnlyFile.setReadOnly()

        repeat(5) { i ->
            AppLogger.d("Failing entry $i")
        }

        waitForChannelDrain()

        val newDir = tmpFolder.newFolder("logs_reinit")
        AppLogger.initForTest(newDir, enabled = true)

        val newMarker = "REINITIALIZED_SUCCESS_MARKER"
        AppLogger.d(newMarker)
        waitForLogMarker(newMarker, targetDir = newDir)

        val newLogFile = File(newDir, "logs.txt")
        assertTrue("New log file should contain new marker", newLogFile.readText().contains(newMarker))
    }

    @Test
    fun `initForTest after shutdown restores file logging in new directory`() {
        AppLogger.shutdown()

        val newDir = tmpFolder.newFolder("logs_after_shutdown")
        AppLogger.initForTest(newDir, enabled = true)

        val marker = "LOG_AFTER_REINIT_FROM_SHUTDOWN"
        AppLogger.d(marker)
        waitForLogMarker(marker, targetDir = newDir)

        val newLogFile = File(newDir, "logs.txt")
        assertTrue("New log file should contain marker logged after reinit", newLogFile.readText().contains(marker))
    }

    private fun waitForLogMarker(
        marker: String,
        targetDir: File = logDir,
        timeoutMs: Long = 10000L,
    ) {
        val logFile = File(targetDir, "logs.txt")
        pollUntil(timeoutMs, "Log marker '$marker' in ${targetDir.name}/logs.txt") {
            logFile.exists() && runCatching { logFile.readText() }.getOrDefault("").contains(marker)
        }
        assertTrue("AppLogger quiescent state", AppLogger.awaitQuiescentForTest(timeoutMs))
    }

    private fun waitForChannelDrain(timeoutMs: Long = 10000L) {
        assertTrue("AppLogger quiescent state", AppLogger.awaitQuiescentForTest(timeoutMs))
    }
}
