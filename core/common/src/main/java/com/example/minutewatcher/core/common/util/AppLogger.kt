package com.example.minutewatcher.core.common.util

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.BuildConfig
import com.example.minutewatcher.core.common.util.AppLogger.WORKER_JOIN_TIMEOUT_MS
import com.example.minutewatcher.core.common.util.AppLogger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Centralized logging utility.
 * Provides Logcat output and optional file logging (DEBUG only).
 * Guaranteed to be exception-safe for all public methods.
 *
 * **Invariants:**
 * - Single worker coroutine consumes log channel sequentially in FIFO order.
 * - Worker lifecycle changes ([init], [initForTest], [resetForTest], [shutdown]) synchronously stop
 *   previous worker with a time-limited join ([WORKER_JOIN_TIMEOUT_MS]) performed outside state lock.
 * - Monotonic generation numbers ensure obsolete workers stop and return unprocessed entries to the channel.
 * - In-flight entries counter ([hasPendingLogs]) tracks logs currently
 *   being processed by worker.
 * - Entries are enqueued only if file logging is enabled, not shutdown, and log directory is set.
 * - Failed file writes and drained entries on write failure threshold are routed to [logger] with `[DRAIN]` prefix
 *   outside state lock, guaranteeing no silent log losses.
 * - Rotation and file write operations are serialized using `synchronized(this)` monitor.
 */
object AppLogger {
    private const val TAG = "MinuteWatcher"
    private const val MAX_FILE_SIZE = 1_048_576 // 1 MB
    private const val LOG_FILE_NAME = "logs.txt"
    private const val LOG_FILE_OLD_NAME = "logs.old.txt"
    private const val LOG_QUEUE_CAPACITY = 8192
    private const val LOG_BUFFER_SIZE = 8192
    private const val MAX_WRITE_FAILURES = 3
    private const val WORKER_JOIN_TIMEOUT_MS = 2_000L

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

    private val inFlightCount = AtomicInteger(0)
    private var workerGeneration = 0L

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
            isFileLoggingEnabled = BuildConfig.DEBUG
        }
        stopWorkerAndStartNewIfNeeded(startNew = true)
    }

    private fun stopWorkerAndStartNewIfNeeded(startNew: Boolean) {
        val oldJob: Job?
        val newGen: Long
        synchronized(this) {
            workerGeneration++
            newGen = workerGeneration
            oldJob = workerJob
            workerJob = null
        }

        if (oldJob != null) {
            oldJob.cancel()
            runBlocking {
                val joined =
                    withTimeoutOrNull(WORKER_JOIN_TIMEOUT_MS) {
                        oldJob.join()
                    }
                if (joined == null) {
                    Log.w(TAG, "AppLogger: worker join timed out after $WORKER_JOIN_TIMEOUT_MS ms")
                }
            }
        }

        if (startNew) {
            synchronized(this) {
                if (isWorkerStartAllowed(newGen)) {
                    workerJob =
                        logScope.launch {
                            runWorker(newGen)
                        }
                }
            }
        }
    }

    private fun isWorkerStartAllowed(newGen: Long): Boolean {
        if (!isFileLoggingEnabled || isShutdown) return false
        return logDir != null && workerGeneration == newGen
    }

    private suspend fun runWorker(myGeneration: Long) {
        while (isCurrentGeneration(myGeneration)) {
            val entry = logChannel.receiveCatching().getOrNull() ?: break
            handleReceivedEntry(entry, myGeneration)
        }
    }

    private fun handleReceivedEntry(
        entry: LogEntry,
        myGeneration: Long,
    ) {
        inFlightCount.incrementAndGet()
        try {
            if (!isCurrentGeneration(myGeneration)) {
                val returned = logChannel.trySend(entry).isSuccess
                if (!returned) {
                    Log.w(
                        TAG,
                        "AppLogger: failed to return log entry to channel " +
                            "on obsolete worker generation, processing normally",
                    )
                    processLogEntry(entry)
                }
            } else {
                processLogEntry(entry)
            }
        } finally {
            inFlightCount.decrementAndGet()
        }
    }

    private fun isCurrentGeneration(myGeneration: Long): Boolean =
        synchronized(this) {
            workerGeneration == myGeneration
        }

    fun d(message: String) {
        runCatching { logger.d(TAG, message) }
        runCatching { logToFile("D", message) }
    }

    fun w(
        message: String,
        throwable: Throwable? = null,
    ) {
        runCatching { logger.w(TAG, message, throwable) }
        runCatching {
            val fullMessage =
                if (throwable != null) {
                    "$message\n${throwable.stackTraceToString()}"
                } else {
                    message
                }
            logToFile("W", fullMessage)
        }
    }

    fun e(
        message: String,
        throwable: Throwable? = null,
    ) {
        runCatching { logger.e(TAG, message, throwable) }
        runCatching {
            val fullMessage =
                if (throwable != null) {
                    "$message\n${throwable.stackTraceToString()}"
                } else {
                    message
                }
            logToFile("E", fullMessage)
        }
    }

    private fun logToFile(
        level: String,
        message: String,
    ) {
        if (!isFileLoggingEnabled || isShutdown || logDir == null) return
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
                    drainedEntries.add(entry)
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
                drainedEntries.add(entry)
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
    internal fun shutdown() {
        synchronized(this) {
            isShutdown = true
            isFileLoggingEnabled = false
            closeWriter()
        }
        stopWorkerAndStartNewIfNeeded(startNew = false)
    }

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(this) {
            isShutdown = false
            isFileLoggingEnabled = BuildConfig.DEBUG
            consecutiveWriteFailures = 0
            closeWriter()
            logDir = null
            logger = AndroidLogger()
            while (logChannel.tryReceive().isSuccess) {
                // Drain channel
            }
        }
        stopWorkerAndStartNewIfNeeded(startNew = false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @VisibleForTesting
    internal fun hasPendingLogs(): Boolean = !logChannel.isEmpty || inFlightCount.get() > 0

    /**
     * Initializes the logger for testing with full state reset.
     *
     * Performs a complete reset of the test state (including clearing the shutdown flag)
     * and restarts the file logging worker if file logging is [enabled].
     */
    @VisibleForTesting
    internal fun initForTest(
        dir: File,
        enabled: Boolean = true,
    ) {
        synchronized(this) {
            isShutdown = false
            closeWriter()
            consecutiveWriteFailures = 0
            logDir = dir
            logger = AndroidLogger()
            isFileLoggingEnabled = enabled
            while (logChannel.tryReceive().isSuccess) {
                // Drain channel
            }
        }
        stopWorkerAndStartNewIfNeeded(startNew = enabled)
    }
}
