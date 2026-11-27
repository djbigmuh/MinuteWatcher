package com.github.djbigmuh.minutewatcher.core.common.util

import android.content.Context
import com.github.djbigmuh.minutewatcher.core.common.testing.AppLoggerResetRule
import com.github.djbigmuh.minutewatcher.core.common.testing.DefaultTimeZoneRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
    val defaultTimeZoneRule = DefaultTimeZoneRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var logDir: File

    @Before
    fun setup() {
        logDir = tmpFolder.newFolder("logs")
        AppLogger.initForTest(logDir)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    @Test
    fun `test log rotation occurs and new entries go to new file`() =
        runTest {
            val messageSize = 1024
            val largeMessage = "A".repeat(messageSize)

            // Write ~3.0 MB (3000 * 1KB) to trigger rotation (MAX_FILE_SIZE = 1MB)
            repeat(3000) {
                AppLogger.d(largeMessage)
            }

            val rotationMarker = "ROTATION_MARKER_TEST_1"
            AppLogger.d(rotationMarker)
            waitForLogMarker(rotationMarker, timeoutMs = 30000L)

            val logFile = File(logDir, "logs.txt")
            val oldFile = File(logDir, "logs.old.txt")

            assertTrue("logs.old.txt should exist after rotation", oldFile.exists())
            assertTrue("logs.txt should exist after rotation", logFile.exists())

            assertTrue("logs.txt should be smaller than logs.old.txt", logFile.length() < oldFile.length())

            val uniqueTag = "UNIQUE_LOG_TAG_12345"
            AppLogger.d(uniqueTag)
            waitForLogMarker(uniqueTag, timeoutMs = 30000L)

            assertTrue("New logs should be in logs.txt", logFile.readText().contains(uniqueTag))
            assertFalse("New logs should NOT be in logs.old.txt", oldFile.readText().contains(uniqueTag))
        }

    @Test
    fun `public methods do not throw when logger or dir is missing`() {
        AppLogger.resetForTest() // Ensure not initialized

        AppLogger.d("test")
        AppLogger.w("test", RuntimeException())
        AppLogger.e("test", RuntimeException())

        val logFile = File(tmpFolder.root, "logs.txt")
        assertFalse("Log file should not be created when logger is uninitialized", logFile.exists())
    }

    @Test
    fun `shutdown prevents file logging`() =
        runTest {
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
    fun `multiple log entries maintain original order and correct format`() =
        runTest {
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
    fun `exceeding MAX_FILE_SIZE triggers rotation and retains old and new logs`() =
        runTest {
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
            assertTrue(
                "logs.old.txt should contain pre-rotation logs",
                oldFile.readText().contains("Pre-rotation chunk 0"),
            )
            assertTrue("logs.txt should contain post-rotation marker", newFile.readText().contains(postRotationMarker))
        }

    @Test
    fun `consecutive write failures disable file logging and public methods do not throw`() =
        runTest {
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

            assertTrue("Read-only log file should exist", readOnlyFile.exists())
        }

    @Test
    fun `drain routes logs to custom logger according to their level`() =
        runTest {
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
        AppLogger.initForTest(nonExistentDir)
        AppLogger.shutdown()

        AppLogger.d("Should be ignored")
        AppLogger.w("Should be ignored")
        AppLogger.e("Should be ignored")

        val logFile = File(nonExistentDir, "logs.txt")
        assertFalse("Log file should not be created after shutdown", logFile.exists())
    }

    @Test
    fun `re-initializing for test with new directory switches target and closes previous writer`() =
        runTest {
            val dir1 = tmpFolder.newFolder("logs1")
            val dir2 = tmpFolder.newFolder("logs2")

            AppLogger.initForTest(dir1, Dispatchers.Unconfined)
            AppLogger.d("Log in dir1")
            waitForLogMarker("Log in dir1", targetDir = dir1)

            AppLogger.initForTest(dir2, Dispatchers.Unconfined)
            AppLogger.d("Log in dir2")
            waitForLogMarker("Log in dir2", targetDir = dir2)

            assertFalse(File(dir1, "logs.txt").readText().contains("Log in dir2"))
            assertTrue(File(dir2, "logs.txt").readText().contains("Log in dir2"))
        }

    @Test
    fun `rotation failure when directory is read only retains active log file content`() =
        runTest {
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
    fun `reinitializing for test resets failure counter and reenables file logging`() =
        runTest {
            val readOnlyFile = File(logDir, "logs.txt")
            readOnlyFile.createNewFile()
            readOnlyFile.setReadOnly()

            repeat(5) { i ->
                AppLogger.d("Failing entry $i")
            }

            waitForChannelDrain()

            val newDir = tmpFolder.newFolder("logs_reinit")
            AppLogger.initForTest(newDir)

            val newMarker = "REINITIALIZED_SUCCESS_MARKER"
            AppLogger.d(newMarker)
            waitForLogMarker(newMarker, targetDir = newDir)

            val newLogFile = File(newDir, "logs.txt")
            assertTrue("New log file should contain new marker", newLogFile.readText().contains(newMarker))
        }

    @Test
    fun `initForTest after shutdown restores file logging in new directory`() =
        runTest {
            AppLogger.shutdown()

            val newDir = tmpFolder.newFolder("logs_after_shutdown")
            AppLogger.initForTest(newDir)

            val marker = "LOG_AFTER_REINIT_FROM_SHUTDOWN"
            AppLogger.d(marker)
            waitForLogMarker(marker, targetDir = newDir)

            val newLogFile = File(newDir, "logs.txt")
            assertTrue("New log file should contain marker logged after reinit", newLogFile.readText().contains(marker))
        }

    @Test
    fun `formatter updates when default timezone changes`() {
        defaultTimeZoneRule.setTimeZone(java.util.TimeZone.getTimeZone("Europe/London"))
        val formatter1 = AppLogger.getFormatter()
        assertEquals(java.time.ZoneId.of("Europe/London"), formatter1.zone)

        defaultTimeZoneRule.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        val formatter2 = AppLogger.getFormatter()
        assertEquals(java.time.ZoneId.of("Asia/Tokyo"), formatter2.zone)
    }

    @Test
    fun `repeated init with different directory writes subsequent logs to new directory`() =
        runTest {
            val dir1 = tmpFolder.newFolder("init_dir1")
            val dir2 = tmpFolder.newFolder("init_dir2")
            val ctx1 =
                mock<Context> {
                    on { filesDir } doReturn dir1
                }
            val ctx2 =
                mock<Context> {
                    on { filesDir } doReturn dir2
                }

            AppLogger.init(ctx1)
            AppLogger.d("Log in dir1")
            waitForLogMarker("Log in dir1", targetDir = dir1)

            AppLogger.init(ctx2)
            AppLogger.d("Log in dir2")
            waitForLogMarker("Log in dir2", targetDir = dir2)

            assertFalse(File(dir1, "logs.txt").readText().contains("Log in dir2"))
            assertTrue(File(dir2, "logs.txt").readText().contains("Log in dir2"))
        }

    @Test
    fun `logs in channel during generation change are not lost`() =
        runTest {
            val dir1 = tmpFolder.newFolder("gen_dir1")
            val dir2 = tmpFolder.newFolder("gen_dir2")
            val ctx1 =
                mock<Context> {
                    on { filesDir } doReturn dir1
                }
            val ctx2 =
                mock<Context> {
                    on { filesDir } doReturn dir2
                }

            AppLogger.init(ctx1)
            repeat(50) { i ->
                AppLogger.d("Batch entry $i")
            }
            AppLogger.init(ctx2)

            val logFiles = listOf(File(dir1, "logs.txt"), File(dir2, "logs.txt"))
            pollUntil(10000L, "all batch entries across logger generations") {
                val combined =
                    logFiles.joinToString("\\n") { file ->
                        if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
                    }
                (0 until 50).all { index -> combined.contains("Batch entry $index") }
            }
        }

    @Test
    fun `init completes without blocking calling thread`() =
        runTest {
            val dir = tmpFolder.newFolder("nonblocking_dir")
            val mockContext =
                mock<Context> {
                    on { filesDir } doReturn dir
                }

            backgroundScope.launch(Dispatchers.Unconfined) {
                AppLogger.init(mockContext)
                AppLogger.d("Non-blocking test log")
            }
            waitForLogMarker("Non-blocking test log", targetDir = dir)
        }

    @Test
    fun `worker generation changes preserve FIFO order`() =
        runTest {
            class FirstLogger : Logger by AndroidLogger()
            class SecondLogger : Logger by AndroidLogger()

            val dir = tmpFolder.newFolder("generation_fifo_dir")
            val mockContext =
                mock<Context> {
                    on { filesDir } doReturn dir
                }

            AppLogger.init(mockContext, FirstLogger())
            repeat(50) { index ->
                AppLogger.d("Generation FIFO entry $index")
            }

            // A different logger implementation forces a worker-generation restart
            // while keeping all entries in the same file for an order assertion.
            AppLogger.init(mockContext, SecondLogger())

            val marker = "GENERATION_FIFO_COMPLETE"
            AppLogger.d(marker)
            waitForLogMarker(marker, targetDir = dir)

            val entries =
                File(dir, "logs.txt")
                    .readLines()
                    .mapNotNull { line ->
                        Regex("""Generation FIFO entry (\d+)""")
                            .find(line)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt()
                    }
            assertEquals((0 until 50).toList(), entries)
        }

    @Test
    fun `shutdown stops worker and subsequent logs are ignored`() =
        runTest {
            val dir = tmpFolder.newFolder("shutdown_dir")
            val mockContext =
                mock<Context> {
                    on { filesDir } doReturn dir
                }
            AppLogger.init(mockContext)
            repeat(20) { index ->
                AppLogger.d("Pending before shutdown $index")
            }
            AppLogger.shutdown()
            assertFalse("Shutdown should clear pending file-log entries", AppLogger.hasPendingLogs())

            AppLogger.d("Ignored message")
            waitForChannelDrain()

            val logFile = File(dir, "logs.txt")
            assertFalse(logFile.exists() && logFile.readText().contains("Ignored message"))
        }

    @Test
    fun `queue overflow increments dropped entries counter without throwing exception`() =
        runTest {
            val initialDropped = AppLogger.droppedEntries
            repeat(10_000) { idx ->
                AppLogger.d("Overflow message $idx")
            }
            waitForChannelDrain()
            assertTrue(
                "Dropped entries counter should record evicted messages",
                AppLogger.droppedEntries > initialDropped,
            )
        }

    @Test
    fun `write error triggers consecutive failure limit and disables file logging`() =
        runTest {
            val badDir = tmpFolder.newFolder("write_error_dir")
            AppLogger.initForTest(badDir)

            val logFile = File(badDir, "logs.txt")
            logFile.createNewFile()
            logFile.setReadOnly()

            repeat(10) { i ->
                AppLogger.d("Message on failing file $i")
            }

            waitForChannelDrain()

            AppLogger.d("Post failure message")
            AppLogger.w("Post failure warning")
            AppLogger.e("Post failure error")
        }

    @Test
    fun `processing completion drains pending logs and reaches quiescent state`() =
        runTest {
            repeat(100) { i ->
                AppLogger.d("Quiescent entry $i")
            }
            AppLogger.awaitQuiescent()
            assertFalse("AppLogger should have no pending logs after awaitQuiescent", AppLogger.hasPendingLogs())
        }

    @Test
    fun `absence of state leaks between tests when using reset rule`() =
        runTest {
            assertEquals("Dropped entries should be 0 on fresh test start", 0L, AppLogger.droppedEntries)
            assertFalse("Pending logs should be false on fresh test start", AppLogger.hasPendingLogs())
        }

    @Test
    fun `custom dispatcher and scope injection operates correctly`() =
        runTest {
            val customDir = tmpFolder.newFolder("custom_scope_dir")
            val customDispatcher = kotlinx.coroutines.Dispatchers.Default
            val customScope = kotlinx.coroutines.CoroutineScope(customDispatcher + kotlinx.coroutines.SupervisorJob())

            AppLogger.initForTest(customDir, customDispatcher = customDispatcher, customScope = customScope)
            AppLogger.d("Custom scope entry")
            waitForLogMarker("Custom scope entry", targetDir = customDir)

            val file = File(customDir, "logs.txt")
            assertTrue(file.exists() && file.readText().contains("Custom scope entry"))
        }

    private suspend fun waitForLogMarker(
        marker: String,
        targetDir: File = logDir,
        timeoutMs: Long = 10000L,
    ) {
        AppLogger.awaitQuiescent(timeoutMs)
        val logFile = File(targetDir, "logs.txt")
        pollUntil(timeoutMs, "Log marker '$marker' in ${targetDir.name}/logs.txt") {
            logFile.exists() && runCatching { logFile.readText() }.getOrDefault("").contains(marker)
        }
    }

    private suspend fun waitForChannelDrain(timeoutMs: Long = 10000L) {
        AppLogger.awaitQuiescent(timeoutMs)
    }
}
