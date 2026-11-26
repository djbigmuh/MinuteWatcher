package com.github.djbigmuh.minutewatcher.core.common.util

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.github.djbigmuh.minutewatcher.core.common.BuildConfig
import com.github.djbigmuh.minutewatcher.core.common.annotation.RunBlockingBridge
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Centralized logging utility.
 * Provides Logcat output and optional file logging (DEBUG only).
 * Guaranteed to be exception-safe for all public methods.
 *
 * **Invariants:**
 * - Single worker coroutine consumes log channel sequentially in FIFO order.
 * - New worker is registered synchronously under state lock (`init`, `initForTest`);
 *   previous worker exits cooperatively after its generation becomes obsolete,
 *   returning unprocessed entries to the channel; teardown paths (`resetForTest`, `shutdown`)
 *   additionally perform a time-limited join (`WORKER_JOIN_TIMEOUT_MS`) outside state lock.
 * - Monotonic generation numbers ensure obsolete workers stop and return unprocessed entries to the channel.
 * - Pending-entry accounting ([hasPendingLogs]) covers queued and in-flight logs until processing completes.
 * - Entries are enqueued only if file logging is enabled, not shutdown, and log directory is set.
 * - Queue capacity is `LOG_QUEUE_CAPACITY` with `DROP_OLDEST` policy; evictions are counted (`droppedEntries`) and reported via throttled warning through [logger].
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
    private const val WORKER_POLL_INTERVAL_MS = 50L
    private const val DROP_WARNING_INTERVAL = 100L

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

    private val pendingCount = AtomicInteger(0)
    private val enqueueLock = Any()
    private val droppedEntryCount = AtomicLong(0)
    private var workerGeneration = 0L

    /** Total number of entries evicted by the DROP_OLDEST channel policy. */
    @VisibleForTesting
    internal val droppedEntries: Long get() = droppedEntryCount.get()

    private val logChannel =
        Channel<LogEntry>(
            capacity = LOG_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = {
                pendingCount.updateAndGet { maxOf(0, it - 1) }
                val total = droppedEntryCount.incrementAndGet()
                if (total == 1L || total % DROP_WARNING_INTERVAL == 0L) {
                    runCatching {
                        logger.w(TAG, "AppLogger: log queue full, oldest entry dropped (total dropped: $total)")
                    }
                }
            },
        )

    private var dispatcher: CoroutineDispatcher = Dispatchers.IO
    private var logScope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())
    private var workerJob: Job? = null
    private val workerJobs = mutableSetOf<Job>()

    private class LogEntry(
        val level: String,
        val message: String,
        val timestamp: Instant = Instant.now(),
    )

    private var cachedZoneId: ZoneId = ZoneId.systemDefault()
    private var dateFormatFormatter: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(cachedZoneId)

    private fun sanitizeMessage(message: String): String {
        if (BuildConfig.DEBUG) return message
        var sanitized = message
        sanitized = sanitized.replace(Regex("""subId\s*=\s*\d+"""), "subId=***")
        sanitized = sanitized.replace(Regex("""\+?\d{7,15}"""), "***")
        val pattern =
            Regex(
                """\b(usage|subscriber|identity|phone|number)\b[^\s]*""",
                RegexOption.IGNORE_CASE,
            )
        sanitized = sanitized.replace(pattern, "***")
        return sanitized
    }

    internal fun getFormatter(): DateTimeFormatter {
        val currentZone = ZoneId.systemDefault()
        if (currentZone != cachedZoneId) {
            synchronized(this) {
                if (currentZone != cachedZoneId) {
                    cachedZoneId = currentZone
                    dateFormatFormatter =
                        DateTimeFormatter
                            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                            .withZone(cachedZoneId)
                }
            }
        }
        return dateFormatFormatter
    }

    fun init(
        context: Context,
        customLogger: Logger = AndroidLogger(),
        customDispatcher: CoroutineDispatcher = Dispatchers.IO,
        customScope: CoroutineScope = CoroutineScope(customDispatcher + SupervisorJob()),
    ) {
        val filesDir = context.filesDir
        synchronized(this) {
            if (isShutdown) return
            // Stateless logger implementations; equality of class along with dir
            // and flag defines configuration identity.
            if (logDir == filesDir && logger::class == customLogger::class) {
                if (isFileLoggingEnabled == BuildConfig.DEBUG && workerJob?.isActive == true) {
                    return
                }
            }
            closeWriter()
            consecutiveWriteFailures = 0
            logDir = filesDir
            logger = customLogger
            dispatcher = customDispatcher
            logScope = customScope
            isFileLoggingEnabled = BuildConfig.DEBUG
        }
        restartWorker()
    }

    private fun restartWorker() {
        val newGen: Long
        synchronized(this) {
            workerGeneration++
            newGen = workerGeneration
            // Do not cancel the old worker: cancellation can race with receive()
            // after it has removed an entry from the channel.
            val newJob =
                if (isWorkerStartAllowed(newGen)) {
                    logScope.launch { runWorker(newGen) }
                } else {
                    null
                }
            workerJob = newJob
            if (newJob != null) {
                workerJobs.add(newJob)
                newJob.invokeOnCompletion {
                    synchronized(this) {
                        workerJobs.remove(newJob)
                    }
                }
            }
        }
    }

    @RunBlockingBridge("Synchronous logger worker shutdown bridge")
    private fun stopWorkerBlocking() {
        val oldJobs: List<Job>
        synchronized(this) {
            workerGeneration++
            oldJobs = workerJobs.toList()
            workerJob = null
        }
        if (oldJobs.isNotEmpty()) {
            runBlocking { joinAndWarnTimeout(oldJobs) }
        }
    }

    private suspend fun joinAndWarnTimeout(oldJobs: List<Job>) {
        val joined =
            withTimeoutOrNull(WORKER_JOIN_TIMEOUT_MS.milliseconds) {
                oldJobs.forEach { it.join() }
            }
        if (joined == null) {
            try {
                logger.w(
                    TAG,
                    "AppLogger: worker join timed out after $WORKER_JOIN_TIMEOUT_MS ms",
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun isWorkerStartAllowed(newGen: Long): Boolean =
        !(!isFileLoggingEnabled || isShutdown) && logDir != null && workerGeneration == newGen

    private suspend fun runWorker(myGeneration: Long) {
        while (isCurrentGeneration(myGeneration)) {
            val entry =
                withTimeoutOrNull(WORKER_POLL_INTERVAL_MS.milliseconds) {
                    logChannel.receive()
                } ?: continue
            handleReceivedEntry(entry, myGeneration)
        }
    }

    private fun handleReceivedEntry(
        entry: LogEntry,
        myGeneration: Long,
    ) {
        var returnedToQueue = false
        try {
            if (!isCurrentGeneration(myGeneration)) {
                returnedToQueue = logChannel.trySend(entry).isSuccess
                if (!returnedToQueue) {
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
            if (!returnedToQueue) {
                pendingCount.updateAndGet { maxOf(0, it - 1) }
            }
        }
    }

    private fun isCurrentGeneration(myGeneration: Long): Boolean =
        synchronized(this) {
            workerGeneration == myGeneration
        }

    fun d(message: String) {
        val safeMessage = sanitizeMessage(message)
        runCatching { logger.d(TAG, safeMessage) }
        runCatching { logToFile("D", safeMessage) }
    }

    fun w(
        message: String,
        throwable: Throwable? = null,
    ) {
        val safeMessage = sanitizeMessage(message)
        runCatching { logger.w(TAG, safeMessage, throwable) }
        runCatching {
            val fullMessage =
                if (throwable != null) {
                    "$safeMessage\n${throwable.stackTraceToString()}"
                } else {
                    safeMessage
                }
            logToFile("W", fullMessage)
        }
    }

    fun e(
        message: String,
        throwable: Throwable? = null,
    ) {
        val safeMessage = sanitizeMessage(message)
        runCatching { logger.e(TAG, safeMessage, throwable) }
        runCatching {
            val fullMessage =
                if (throwable != null) {
                    "$safeMessage\n${throwable.stackTraceToString()}"
                } else {
                    safeMessage
                }
            logToFile("E", fullMessage)
        }
    }

    private fun logToFile(
        level: String,
        message: String,
    ) {
        synchronized(enqueueLock) {
            if (!isFileLoggingEnabled || isShutdown || logDir == null) return
            pendingCount.incrementAndGet()
            val result = logChannel.trySend(LogEntry(level, message))
            if (result.isFailure) {
                pendingCount.updateAndGet { maxOf(0, it - 1) }
            }
        }
    }

    private fun openWriter(dir: File): BufferedWriter? =
        try {
            val logFile = File(dir, LOG_FILE_NAME)
            val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(logFile, true)), LOG_BUFFER_SIZE)
            synchronized(this) {
                writer = bw
            }
            bw
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Operation must complete without propagating exception for logging infrastructure reliability.
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
        @Suppress("TooGenericExceptionCaught")
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
                    val timestamp = getFormatter().format(entry.timestamp)
                    activeWriter.write("$timestamp ${entry.level}/$TAG: ${entry.message}\n")
                    activeWriter.flush()
                    consecutiveWriteFailures = 0
                }
            } catch (e: Exception) {
                // Operation must complete without propagating exception for stability.
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
                pendingCount.updateAndGet { maxOf(0, it - 1) }
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
        synchronized(enqueueLock) {
            synchronized(this) {
                isShutdown = true
                isFileLoggingEnabled = false
                closeWriter()
            }
        }
        stopWorkerBlocking()
        synchronized(enqueueLock) {
            while (logChannel.tryReceive().isSuccess) {
                pendingCount.updateAndGet { maxOf(0, it - 1) }
            }
        }
    }

    @VisibleForTesting
    internal fun resetForTest() {
        stopWorkerBlocking()
        synchronized(enqueueLock) {
            synchronized(this) {
                isShutdown = false
                isFileLoggingEnabled = BuildConfig.DEBUG
                consecutiveWriteFailures = 0
                droppedEntryCount.set(0)
                closeWriter()
                logDir = null
                logger = AndroidLogger()
            }
            while (logChannel.tryReceive().isSuccess) {
                pendingCount.updateAndGet { maxOf(0, it - 1) }
            }
        }
    }

    @VisibleForTesting
    internal fun hasPendingLogs(): Boolean = pendingCount.get() > 0

    /**
     * Initializes the logger for testing with full state reset.
     *
     * Performs a complete reset of the test state (including clearing the shutdown flag)
     * and restarts the file logging worker.
     */
    @VisibleForTesting
    internal fun initForTest(
        dir: File,
        customDispatcher: CoroutineDispatcher = Dispatchers.IO,
        customScope: CoroutineScope = CoroutineScope(customDispatcher + SupervisorJob()),
    ) {
        stopWorkerBlocking()
        synchronized(enqueueLock) {
            synchronized(this) {
                isShutdown = false
                closeWriter()
                consecutiveWriteFailures = 0
                droppedEntryCount.set(0)
                logDir = dir
                logger = AndroidLogger()
                dispatcher = customDispatcher
                logScope = customScope
                isFileLoggingEnabled = true
            }
            while (logChannel.tryReceive().isSuccess) {
                pendingCount.updateAndGet { maxOf(0, it - 1) }
            }
            // Keep the atomic count derived from enqueue/receive completion events.
            // A timed-out old worker may still finish after stopWorkerBlocking() returns.
        }
        restartWorker()
    }
}
