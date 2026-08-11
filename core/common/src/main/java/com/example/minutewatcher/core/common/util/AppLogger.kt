package com.example.minutewatcher.core.common.util

import android.content.Context
import android.util.Log
import com.example.minutewatcher.core.common.BuildConfig
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Централизованное логирование.
 */
object AppLogger {
    private const val TAG = "MinuteWatcher"
    private const val MAX_FILE_SIZE = 1_048_576 // 1 MB
    private const val LOG_FILE_NAME = "logs.txt"
    private const val LOG_FILE_OLD_NAME = "logs.old.txt"
    private const val LOG_QUEUE_CAPACITY = 1024
    private val IS_FILE_LOGGING_ENABLED = BuildConfig.DEBUG // Disabled in release for privacy and security

    private var logDir: File? = null
    private var logger: Logger = AndroidLogger()
    private val executor by lazy {
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(LOG_QUEUE_CAPACITY),
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
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
        val dir = logDir ?: return
        executor.execute {
            try {
                val logFile = File(dir, LOG_FILE_NAME)
                if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                    val oldFile = File(dir, LOG_FILE_OLD_NAME)
                    if (oldFile.exists() && !oldFile.delete()) {
                        // Не удалось удалить старый файл — пропускаем ротацию в этот раз
                        return@execute
                    }
                    if (!logFile.renameTo(oldFile)) {
                        // Не удалось переименовать — продолжаем писать в текущий файл
                        return@execute
                    }
                }

                val timestamp = dateFormat.format(Instant.now())
                val entry = "$timestamp $level/$TAG: $message\n"

                FileWriter(logFile, true).use { writer ->
                    writer.write(entry)
                }
            } catch (e: Exception) {
                // Fail silently for file logging to avoid recursive errors,
                // but at least log to system Log in debug.
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "File logging failed", e)
                }
            }
        }
    }
}
