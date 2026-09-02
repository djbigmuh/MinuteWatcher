package com.example.minutewatcher.core.common.util

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Централизованное логирование.
 */
@Suppress("TooManyFunctions")
object AppLogger {
    private const val TAG = "MinuteWatcher"
    private const val MAX_FILE_SIZE = 1_048_576 // 1 MB
    private const val LOG_FILE_NAME = "logs.txt"
    private const val LOG_FILE_OLD_NAME = "logs.old.txt"
    private const val LOG_QUEUE_CAPACITY = 8192
    private const val DROPPED_LOGS_REPORT_INTERVAL = 50L

    @Volatile
    private var isFileLoggingEnabled = BuildConfig.DEBUG

    @Volatile
    private var isFileLoggingDegraded = false
    private const val LOG_BUFFER_SIZE = 8192
    private const val WORKER_RETRY_DELAY_MS = 1000L
    private const val MAX_RETRY_DELAY_MS = 5000L
    private const val SYNC_FALLBACK_LOCK_TIMEOUT_MS = 500L
    private const val SHUTDOWN_JOIN_TIMEOUT_MS = 1000L
    private const val SHUTDOWN_FLUSH_LOCK_TIMEOUT_MS = 500L

    @Volatile private var logDir: File? = null

    // Access is guarded by fileLock
    private var logWriter: BufferedWriter? = null

    @Volatile private var logger: Logger = AndroidLogger()

    private val criticalQueue = ArrayDeque<LogEntry>()
    private val normalQueue = ArrayDeque<LogEntry>()
    private val lock = ReentrantLock()
    private val fileLock = ReentrantLock()
    private val condition = lock.newCondition()
    private val droppedCount = AtomicLong(0)
    private val workerRestartCount = AtomicLong(0)
    private val consecutiveWriteFailures = AtomicLong(0)
    private val currentWriteRetryDelay = AtomicLong(WORKER_RETRY_DELAY_MS)

    private const val MAX_WORKER_CRASHES = 3

    @Volatile
    private var isWorkerStarted = false

    @Volatile
    private var workerThread: Thread? = null

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

    private fun logToFile(
        level: String,
        message: String,
    ) {
        val dir = logDir ?: return
        val entry = LogEntry(level, message)
        var fallbackRequired = false

        lock.withLock {
            if (!isFileLoggingEnabled) return

            val totalSize = criticalQueue.size + normalQueue.size
            if (entry.isCritical) {
                fallbackRequired = !enqueueCritical(entry, totalSize)
            } else {
                enqueueNormal(entry, totalSize)
            }
        }

        if (fallbackRequired) {
            handleSynchronousFallback(entry, level, dir)
        } else {
            ensureWorkerStarted()
        }
    }

    @Suppress("ReturnCount")
    private fun enqueueCritical(
        entry: LogEntry,
        totalSize: Int,
    ): Boolean {
        if (totalSize < LOG_QUEUE_CAPACITY) {
            criticalQueue.addLast(entry)
            condition.signalAll()
            return true
        }

        if (normalQueue.pollFirst() != null) {
            criticalQueue.addLast(entry)
            condition.signalAll()
            return true
        }

        return false
    }

    private fun enqueueNormal(
        entry: LogEntry,
        totalSize: Int,
    ) {
        if (totalSize < LOG_QUEUE_CAPACITY) {
            normalQueue.addLast(entry)
            condition.signalAll()
        } else {
            reportDropped()
        }
    }

    private fun handleSynchronousFallback(
        entry: LogEntry,
        level: String,
        dir: File,
    ) {
        Log.w(TAG, "Critical log queue full! Performing synchronous fallback for $level/$TAG")
        val acquired =
            try {
                fileLock.tryLock(SYNC_FALLBACK_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

        if (acquired) {
            try {
                writeEntryToFileUnlocked(entry, dir)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Critical log synchronous write failed", e)
                }
            } finally {
                fileLock.unlock()
            }
        } else {
            val reQueued =
                lock.withLock {
                    if (criticalQueue.size < LOG_QUEUE_CAPACITY) {
                        criticalQueue.addLast(entry)
                        condition.signalAll()
                        true
                    } else {
                        false
                    }
                }

            if (reQueued) {
                Log.w(TAG, "Critical log re-queued due to file lock contention: ${entry.message}")
            } else {
                reportDropped()
                Log.w(
                    TAG,
                    "Critical log dropped from file (lock contention & queue full): [${entry.level}] ${entry.message}",
                )
            }
        }
    }

    private fun ensureWorkerStarted() {
        if (!isFileLoggingEnabled || isFileLoggingDegraded) return
        val current = workerThread
        if ((current != null) && current.isAlive) return

        lock.withLock {
            val fresh = workerThread
            if (isFileLoggingEnabled && ((fresh == null) || !fresh.isAlive)) {
                val count = workerRestartCount.get()
                if (count > 0) {
                    Log.w(TAG, "AppLogger: restarting worker thread (previous crash count: $count)")
                }

                isWorkerStarted = true
                val thread = Thread({ workerLoop() }, "AppLogger-Worker")
                workerThread = thread
                thread.isDaemon = true
                thread.start()
            }
        }
    }

    private fun reportDropped() {
        val count = droppedCount.incrementAndGet()
        if ((count % DROPPED_LOGS_REPORT_INTERVAL) == 1L) {
            Log.w(TAG, "Log queue full! Total dropped/displaced non-critical logs: $count")
        }
    }

    private fun workerLoop() {
        try {
            while (isFileLoggingEnabled) {
                val entry = pollEntry() ?: continue
                processWorkerEntry(entry)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            lock.withLock {
                isWorkerStarted = false
            }
            val count = workerRestartCount.incrementAndGet()
            Log.e(TAG, "AppLogger worker thread crashed (restart count: $count)", e)
            if (count >= MAX_WORKER_CRASHES) {
                degradeFileLogging()
            } else if (isFileLoggingEnabled) {
                Thread {
                    try {
                        Thread.sleep(WORKER_RETRY_DELAY_MS)
                        ensureWorkerStarted()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Error,
        ) {
            lock.withLock {
                isWorkerStarted = false
            }
            degradeFileLogging()
            Log.e(TAG, "AppLogger worker thread fatal error", e)
            throw e
        }
    }

    private fun pollEntry(): LogEntry? =
        lock.withLock {
            while (criticalQueue.isEmpty() && normalQueue.isEmpty() && isFileLoggingEnabled) {
                condition.await()
            }
            criticalQueue.pollFirst() ?: normalQueue.pollFirst()
        }

    private fun processWorkerEntry(entry: LogEntry) {
        val dir = logDir ?: return
        try {
            writeEntryToFile(entry, dir)
            // Success
            if (isFileLoggingDegraded) {
                Log.i(TAG, "AppLogger: file logging restored")
                resetFileLogging()
            } else {
                consecutiveWriteFailures.set(0)
                currentWriteRetryDelay.set(WORKER_RETRY_DELAY_MS)
                workerRestartCount.set(0)
            }
        } catch (e: Exception) {
            val failures = consecutiveWriteFailures.incrementAndGet()
            if (failures >= MAX_WORKER_CRASHES) {
                if (!isFileLoggingDegraded) {
                    degradeFileLogging()
                }
                Log.w(TAG, "[FileLog-Fallback] ${entry.level}/$TAG: ${entry.message}")
            } else {
                lock.withLock {
                    if (entry.isCritical) criticalQueue.addFirst(entry) else normalQueue.addFirst(entry)
                    condition.signalAll()
                }

                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "AppLogger: failed to write log entry, re-queued. Failure count: $failures", e)
                }

                val delay = currentWriteRetryDelay.get()
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                val nextDelay = (delay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                currentWriteRetryDelay.set(nextDelay)
            }
        }
    }

    private fun degradeFileLogging() {
        isFileLoggingDegraded = true
        Log.e(TAG, "AppLogger: too many failures, switching to Logcat-only mode for file logs")

        lock.withLock {
            while (criticalQueue.isNotEmpty()) {
                val entry = criticalQueue.pollFirst() ?: break
                Log.e(TAG, "[CRITICAL-DRAIN] ${entry.level}/$TAG: ${entry.message}")
            }
        }
    }

    @VisibleForTesting
    fun resetFileLogging() {
        isFileLoggingDegraded = false
        isFileLoggingEnabled = true
        consecutiveWriteFailures.set(0)
        workerRestartCount.set(0)
        currentWriteRetryDelay.set(WORKER_RETRY_DELAY_MS)
    }

    private fun writeEntryToFile(
        entry: LogEntry,
        dir: File,
    ) {
        fileLock.withLock {
            writeEntryToFileUnlocked(entry, dir)
        }
    }

    private fun writeEntryToFileUnlocked(
        entry: LogEntry,
        dir: File,
    ) {
        try {
            val logFile = File(dir, LOG_FILE_NAME)
            if (logFile.exists() && (logFile.length() > MAX_FILE_SIZE)) {
                closeWriterUnlocked()
                rotateLogFilesUnlocked(dir, logFile)
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
            closeWriterUnlocked()
            throw e
        }
    }

    private fun rotateLogFilesUnlocked(
        dir: File,
        logFile: File,
    ) {
        val oldFile = File(dir, LOG_FILE_OLD_NAME)
        if (oldFile.exists()) oldFile.delete()
        val renamed = logFile.renameTo(oldFile)
        if (!renamed) {
            logFile.delete()
        }
    }

    private fun closeWriterUnlocked() {
        try {
            logWriter?.close()
        } catch (_: Exception) {
        }
        logWriter = null
    }

    fun shutdown() {
        val dir = logDir
        isFileLoggingEnabled = false
        logDir = null
        lock.withLock {
            condition.signalAll()
        }
        val thread = workerThread
        if (thread != null) {
            thread.interrupt()
            try {
                thread.join(SHUTDOWN_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        if (thread?.isAlive == true) {
            if (fileLock.tryLock(SHUTDOWN_FLUSH_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                try {
                    closeWriterUnlocked()
                } finally {
                    fileLock.unlock()
                }
            }
        } else {
            fileLock.withLock {
                dir?.let { flushRemainingLogsUnlocked(it) }
                closeWriterUnlocked()
            }
        }
        workerThread = null
    }

    private fun flushRemainingLogsUnlocked(dir: File) {
        if (!lock.tryLock()) return
        try {
            while (criticalQueue.isNotEmpty() || normalQueue.isNotEmpty()) {
                flushNextEntry(dir)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun flushNextEntry(dir: File) {
        val entry = criticalQueue.pollFirst() ?: normalQueue.pollFirst()
        if (entry != null) {
            try {
                writeEntryToFileUnlocked(entry, dir)
            } catch (_: Exception) {
            }
        }
    }

    @VisibleForTesting
    fun resetForTest() {
        fileLock.withLock {
            closeWriterUnlocked()
            logDir = null
        }
        lock.withLock {
            criticalQueue.clear()
            normalQueue.clear()
        }
        resetFileLogging()
    }

    @VisibleForTesting
    fun getQueueSizeForTest(): Int =
        lock.withLock {
            criticalQueue.size + normalQueue.size
        }

    @VisibleForTesting
    fun initForTest(
        dir: File,
        enabled: Boolean = true,
    ) {
        logDir = dir
        isFileLoggingEnabled = enabled
    }
}
