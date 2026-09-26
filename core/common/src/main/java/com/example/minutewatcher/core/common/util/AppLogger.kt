package com.example.minutewatcher.core.common.util

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.BuildConfig
import com.example.minutewatcher.core.common.util.AppLogger.init
import com.example.minutewatcher.core.common.util.AppLogger.initForTest
import com.example.minutewatcher.core.common.util.AppLogger.resetForTest
import com.example.minutewatcher.core.common.util.AppLogger.shutdown
import com.example.minutewatcher.core.common.util.AppLogger.writer
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
 * - The buffered file writer [writer] belongs exclusively to the single worker coroutines job.
 * - Consumes the log channel sequentially in FIFO order.
 * - Opened lazily on first log entry and closed on file rotation, file logging disablement,
 *   [shutdown], or test state reset ([resetForTest], [initForTest], [init]).
 * - Access from other threads is synchronized via `synchronized(this)`.
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
            Log.d(TAG, "AppLogger.d failed: $message", e)
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

    private var consecutiveWriteFailures = 0
    private const val MAX_WRITE_FAILURES = 3

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
        val dir = logDir ?: return
        try {
            val logFile = File(dir, LOG_FILE_NAME)
            var activeWriter = writer

            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                closeWriter()
                rotateLogFiles(dir, logFile)
                activeWriter = openWriter(dir)
            } else if (activeWriter == null) {
                activeWriter = openWriter(dir)
            }

            if (activeWriter == null) {
                consecutiveWriteFailures++
                handleWriteFailure("failed to open writer")
                return
            }

            val timestamp = dateFormat.format(entry.timestamp)
            activeWriter.write("$timestamp ${entry.level}/$TAG: ${entry.message}\n")
            activeWriter.flush()
            consecutiveWriteFailures = 0
        } catch (e: Exception) {
            closeWriter()
            consecutiveWriteFailures++
            Log.e(TAG, "AppLogger: failed to write log to file (failure $consecutiveWriteFailures)", e)
            handleWriteFailure("write exception")
        }
    }

    private fun handleWriteFailure(reason: String) {
        if (consecutiveWriteFailures >= MAX_WRITE_FAILURES) {
            Log.e(TAG, "AppLogger: too many failures ($reason), disabling file logging for this session")
            isFileLoggingEnabled = false
            closeWriter()
            // Drain the channel to Logcat
            while (true) {
                val next = logChannel.tryReceive().getOrNull() ?: break
                Log.d(TAG, "[DRAIN] ${next.level}/$TAG: ${next.message}")
            }
        }
    }

    private fun rotateLogFiles(
        dir: File,
        logFile: File,
    ) {
        val oldFile = File(dir, LOG_FILE_OLD_NAME)
        if (oldFile.exists()) oldFile.delete()
        if (!logFile.renameTo(oldFile)) {
            logFile.delete()
        }
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

    @VisibleForTesting
    fun initForTest(
        dir: File,
        enabled: Boolean = true,
    ) {
        synchronized(this) {
            closeWriter()
            logDir = dir
            isFileLoggingEnabled = enabled
            ensureWorkerStarted()
        }
    }
}
