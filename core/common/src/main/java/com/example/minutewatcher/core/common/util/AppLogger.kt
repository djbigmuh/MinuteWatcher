package com.example.minutewatcher.core.common.util

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.BuildConfig
import com.example.minutewatcher.core.common.util.AppLogger.closeWriter
import com.example.minutewatcher.core.common.util.AppLogger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Centralized logging utility.
 * Provides Logcat output and optional file logging (DEBUG only).
 * Guaranteed to be exception-safe for all public methods.
 *
 * **Invariants:**
 * - Worker file operations (file size checks, writer opening, writing, flushing, rotation, closing)
 *   and lifecycle methods ([init], [initForTest], [resetForTest], [shutdown], [closeWriter]) are serialized
 *   using the `synchronized(this)` monitor.
 * - During rotation, the active log file is neither deleted nor truncated until its contents have been
 *   successfully transferred to `logs.old.txt`.
 * - Consumes the log channel sequentially in FIFO order.
 * - Drains channel entries to [logger] retaining their original severity level upon file logging disablement.
 * - Opened lazily on first log entry and closed on file rotation, file logging disablement,
 *   [shutdown], or test state reset ([resetForTest], [initForTest], [init]).
 *
 * NOTE: Suppressing TooManyFunctions as this component manages complex multi-threaded
 * logging lifecycle, including coroutine-based worker, file rotation and test utilities.
 */
@Suppress("TooManyFunctions")
object AppLogger {
    private const val TAG = "MinuteWatcher"
    private const val MAX_FILE_SIZE = 1_048_576 // 1 MB
    private const val LOG_FILE_NAME = "logs.txt"
    private const val LOG_FILE_OLD_NAME = "logs.old.txt"
    private const val LOG_QUEUE_CAPACITY = 8192
    private const val LOG_BUFFER_SIZE = 8192
    private const val MAX_WRITE_FAILURES = 3

    @Volatile
    private var isFileLoggingEnabled = BuildConfig.DEBUG

    @Volatile
    private var isShutdown = false

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var logger: Logger = AndroidLogger()

    @Volatile
    private var writer: BufferedWriter? = null

    private var consecutiveWriteFailures = 0

    private val logChannel =
        Channel<LogEntry>(
            capacity = LOG_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var workerJob: Job? = null

    private class LogEntry(
        val level: String,
        val message: String,
        val timestamp: Instant = Instant.now(),
    )

    private val dateFormat =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())

    fun init(
        context: Context,
        customLogger: Logger = AndroidLogger(),
    ) {
        synchronized(this) {
            if (isShutdown) return
            closeWriter()
            consecutiveWriteFailures = 0
            logDir = context.filesDir
            logger = customLogger
            ensureWorkerStarted()
        }
    }

    private fun ensureWorkerStarted() {
        if (isFileLoggingEnabled && (workerJob == null || workerJob?.isActive == false)) {
            workerJob =
                logScope.launch {
                    logChannel.receiveAsFlow().collect { entry ->
                        processLogEntry(entry)
                    }
                }
        }
    }

    fun d(message: String) {
        try {
            logger.d(TAG, message)
            logToFile("D", message)
        } catch (e: Exception) {
            Log.w(TAG, "AppLogger.d failed: $message", e)
        }
    }

    fun w(
        message: String,
        throwable: Throwable? = null,
    ) {
        try {
            logger.w(TAG, message, throwable)
            val fullMessage =
                if (throwable != null) {
                    "$message\n${throwable.stackTraceToString()}"
                } else {
                    message
                }
            logToFile("W", fullMessage)
        } catch (e: Exception) {
            Log.w(TAG, "AppLogger.w failed: $message", e)
        }
    }

    fun e(
        message: String,
        throwable: Throwable? = null,
    ) {
        try {
            logger.e(TAG, message, throwable)
            val fullMessage =
                if (throwable != null) {
                    "$message\n${throwable.stackTraceToString()}"
                } else {
                    message
                }
            logToFile("E", fullMessage)
        } catch (e: Exception) {
            Log.e(TAG, "AppLogger.e failed: $message", e)
        }
    }

    private fun logToFile(
        level: String,
        message: String,
    ) {
        if (!isFileLoggingEnabled || isShutdown) return
        logChannel.trySend(LogEntry(level, message))
    }

    private fun openWriter(dir: File): BufferedWriter? =
        try {
            val logFile = File(dir, LOG_FILE_NAME)
            val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(logFile, true)), LOG_BUFFER_SIZE)
            synchronized(this) {
                writer = bw
            }
            bw
        } catch (e: Exception) {
            Log.e(TAG, "AppLogger: failed to open log file writer", e)
            null
        }

    private fun closeWriter() {
        synchronized(this) {
            val currentWriter = writer
            writer = null
            if (currentWriter != null) {
                runCatching { currentWriter.close() }
            }
        }
    }

    private fun processLogEntry(entry: LogEntry) {
        val drainedEntries = mutableListOf<LogEntry>()
        synchronized(this) {
            val dir = logDir
            if (!isFileLoggingEnabled || isShutdown || dir == null) return
            try {
                val logFile = File(dir, LOG_FILE_NAME)

                if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                    rotateLogFiles(dir)
                } else if (writer == null) {
                    openWriter(dir)
                }

                val activeWriter = writer
                if (activeWriter == null) {
                    consecutiveWriteFailures++
                    handleWriteFailureLocked("failed to open writer", drainedEntries)
                } else {
                    val timestamp = dateFormat.format(entry.timestamp)
                    activeWriter.write("$timestamp ${entry.level}/$TAG: ${entry.message}\n")
                    activeWriter.flush()
                    consecutiveWriteFailures = 0
                }
            } catch (e: Exception) {
                closeWriter()
                consecutiveWriteFailures++
                Log.e(TAG, "AppLogger: failed to write log to file (failure $consecutiveWriteFailures)", e)
                handleWriteFailureLocked("write exception", drainedEntries)
            }
        }

        if (drainedEntries.isNotEmpty()) {
            for (next in drainedEntries) {
                val drainMessage = "[DRAIN] ${next.level}/$TAG: ${next.message}"
                when (next.level) {
                    "W" -> logger.w(TAG, drainMessage)
                    "E" -> logger.e(TAG, drainMessage)
                    else -> logger.d(TAG, drainMessage)
                }
            }
        }
    }

    private fun handleWriteFailureLocked(
        reason: String,
        drainedEntries: MutableList<LogEntry>,
    ) {
        if (consecutiveWriteFailures >= MAX_WRITE_FAILURES) {
            Log.e(TAG, "AppLogger: too many failures ($reason), disabling file logging for this session")
            isFileLoggingEnabled = false
            closeWriter()
            while (true) {
                val next = logChannel.tryReceive().getOrNull() ?: break
                drainedEntries.add(next)
            }
        }
    }

    private fun rotateLogFiles(dir: File) {
        closeWriter()

        val logFile = File(dir, LOG_FILE_NAME)
        val oldFile = File(dir, LOG_FILE_OLD_NAME)

        if (oldFile.exists()) {
            runCatching { oldFile.delete() }
        }

        val moved = runCatching { logFile.renameTo(oldFile) }.getOrDefault(false)

        if (!moved) {
            val copySuccess =
                runCatching {
                    logFile.inputStream().use { input ->
                        oldFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                }.getOrDefault(false)

            if (copySuccess) {
                val truncateSuccess =
                    runCatching {
                        FileOutputStream(logFile, false).use { }
                        true
                    }.getOrDefault(false)

                if (!truncateSuccess) {
                    Log.e(TAG, "AppLogger: failed to truncate active log file after copy")
                }
            } else {
                Log.e(TAG, "AppLogger: log file rotation failed (move and copy failed)")
            }
        }

        openWriter(dir)
    }

    /**
     * Shuts down the logger and stops the file worker.
     *
     * Used only in tests to ensure clean state and avoid coroutine leaks.
     * Production code should not call this as the logger should live for the app lifetime.
     */
    @VisibleForTesting
    fun shutdown() {
        synchronized(this) {
            isShutdown = true
            isFileLoggingEnabled = false
            closeWriter()
            workerJob?.cancel()
            workerJob = null
            // We don't close the channel to avoid IllegalStateException on trySend
        }
    }

    @VisibleForTesting
    fun resetForTest() {
        synchronized(this) {
            isShutdown = false
            isFileLoggingEnabled = BuildConfig.DEBUG
            consecutiveWriteFailures = 0
            closeWriter()
            logDir = null
            workerJob?.cancel()
            workerJob = null
            // Drain channel
            while (logChannel.tryReceive().isSuccess) { // empty
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @VisibleForTesting
    fun hasPendingLogsForTest(): Boolean = !logChannel.isEmpty

    /**
     * Initializes the logger for testing with full state reset.
     *
     * Performs a complete reset of the test state (including clearing the shutdown flag)
     * and restarts the file logging worker if file logging is [enabled].
     */
    @VisibleForTesting
    fun initForTest(
        dir: File,
        enabled: Boolean = true,
    ) {
        synchronized(this) {
            isShutdown = false
            closeWriter()
            consecutiveWriteFailures = 0
            logDir = dir
            isFileLoggingEnabled = enabled
            ensureWorkerStarted()
        }
    }
}
