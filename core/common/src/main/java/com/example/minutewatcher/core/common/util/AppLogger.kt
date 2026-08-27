package com.example.minutewatcher.core.common.util

import android.content.Context
import android.util.Log
import com.example.minutewatcher.core.common.BuildConfig
import com.example.minutewatcher.core.common.util.AppLogger.MAX_FILE_SIZE
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Централизованное логирование.
 */
object AppLogger {
    private const val TAG = "MinuteWatcher"
    private const val MAX_FILE_SIZE = 1_048_576 // 1 MB
    private const val LOG_FILE_NAME = "logs.txt"
    private const val LOG_FILE_OLD_NAME = "logs.old.txt"
    private const val LOG_QUEUE_CAPACITY = 4096
    private const val DROPPED_LOGS_REPORT_INTERVAL = 50L
    private val IS_FILE_LOGGING_ENABLED = BuildConfig.DEBUG
    private const val LOG_BUFFER_SIZE = 8192

    @Volatile private var logDir: File? = null

    @Volatile private var logWriter: BufferedWriter? = null

    @Volatile private var logger: Logger = AndroidLogger()

    private val criticalQueue = ArrayDeque<LogEntry>()
    private val normalQueue = ArrayDeque<LogEntry>()
    private val lock = ReentrantLock()
    private val fileLock = ReentrantLock()
    private val condition = lock.newCondition()
    private val droppedCount = AtomicLong(0)

    @Volatile private var isWorkerStarted = false

    private class LogEntry(
        val level: String,
        val message: String,
        val timestamp: Instant = Instant.now(),
    ) {
        val isCritical: Boolean = (level == "E") || (level == "W")
    }

    private val dateFormat =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())

    fun init(
        context: Context,
        customLogger: Logger = AndroidLogger(),
    ) {
        logDir = context.filesDir
        logger = customLogger
    }

    fun d(message: String) {
        logger.d(TAG, message)
        logToFile("D", message)
    }

    fun w(
        message: String,
        throwable: Throwable? = null,
    ) {
        logger.w(TAG, message, throwable)
        val fullMessage =
            if (throwable != null) {
                "$message\n${throwable.stackTraceToString()}"
            } else {
                message
            }
        logToFile("W", fullMessage)
    }

    fun e(
        message: String,
        throwable: Throwable? = null,
    ) {
        logger.e(TAG, message, throwable)
        val fullMessage =
            if (throwable != null) {
                "$message\n${throwable.stackTraceToString()}"
            } else {
                message
            }
        logToFile("E", fullMessage)
    }

    /**
     * Logs the message to a file in the application's internal directory.
     *
     * Rotation logic:
     * When the current log file exceeds [MAX_FILE_SIZE], it is renamed to "logs.old.txt",
     * replacing any existing "logs.old.txt". This limits the total log storage to
     * approximately 2 * [MAX_FILE_SIZE].
     */
    private fun logToFile(
        level: String,
        message: String,
    ) {
        if (!IS_FILE_LOGGING_ENABLED) return
        if (logDir == null) return

        val entry = LogEntry(level, message)
        var fallbackRequired = false

        lock.withLock {
            val totalSize = criticalQueue.size + normalQueue.size
            if (entry.isCritical) {
                if (totalSize >= LOG_QUEUE_CAPACITY) {
                    // Try to make room by displacing a non-critical log.
                    if (normalQueue.pollFirst() != null) {
                        criticalQueue.addLast(entry)
                        condition.signalAll()
                    } else {
                        // All capacity is taken by critical logs.
                        // Fallback to synchronous recording to avoid losing this critical log.
                        fallbackRequired = true
                    }
                } else {
                    criticalQueue.addLast(entry)
                    condition.signalAll()
                }
            } else {
                if (totalSize < LOG_QUEUE_CAPACITY) {
                    normalQueue.addLast(entry)
                    condition.signalAll()
                } else {
                    reportDropped()
                }
            }
        }

        if (fallbackRequired) {
            // Synchronous fallback for critical logs when queue is full.
            reportDropped()
            Log.w(TAG, "Critical log queue full! Performing synchronous fallback for $level/$TAG")
            writeEntryToFile(entry)
        } else {
            ensureWorkerStarted()
        }
    }

    private fun ensureWorkerStarted() {
        if (isWorkerStarted) return
        lock.withLock {
            if (isWorkerStarted) return
            isWorkerStarted = true
            val thread = Thread({ workerLoop() }, "AppLogger-Worker")
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun reportDropped() {
        val count = droppedCount.incrementAndGet()
        if (count % DROPPED_LOGS_REPORT_INTERVAL == 1L) {
            Log.w(TAG, "Log queue full! Total dropped/displaced non-critical logs: $count")
        }
    }

    private fun workerLoop() {
        while (true) {
            val entry =
                lock.withLock {
                    while (criticalQueue.isEmpty() && normalQueue.isEmpty()) {
                        condition.await()
                    }
                    criticalQueue.pollFirst() ?: normalQueue.pollFirst()
                } ?: continue

            writeEntryToFile(entry)
        }
    }

    private fun writeEntryToFile(entry: LogEntry) {
        val dir = logDir ?: return
        fileLock.withLock {
            try {
                val logFile = File(dir, LOG_FILE_NAME)
                if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                    closeWriter()

                    val oldFile = File(dir, LOG_FILE_OLD_NAME)
                    if (oldFile.exists()) oldFile.delete()
                    logFile.renameTo(oldFile)
                }

                if (logWriter == null) {
                    logWriter =
                        BufferedWriter(
                            OutputStreamWriter(FileOutputStream(logFile, true)),
                            LOG_BUFFER_SIZE,
                        )
                }

                val timestamp = dateFormat.format(entry.timestamp)
                val line = "$timestamp ${entry.level}/$TAG: ${entry.message}\n"
                logWriter?.write(line)
                logWriter?.flush()
            } catch (e: Exception) {
                closeWriter()
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "File logging failed", e)
                }
            }
        }
    }

    private fun closeWriter() {
        fileLock.withLock {
            try {
                logWriter?.close()
            } catch (_: Exception) {
            }
            logWriter = null
        }
    }
}
