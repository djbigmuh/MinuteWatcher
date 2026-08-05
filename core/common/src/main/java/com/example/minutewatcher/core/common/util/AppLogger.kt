package com.example.minutewatcher.core.common.util

import android.content.Context
import com.example.minutewatcher.core.common.BuildConfig
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Централизованное логирование.
 */
object AppLogger {
    private const val TAG = "MinuteWatcher"
    private const val MAX_FILE_SIZE = 1 * 1024 * 1024 // 1 MB
    private const val LOG_FILE_NAME = "logs.txt"
    private const val LOG_FILE_OLD_NAME = "logs.old.txt"
    private val IS_FILE_LOGGING_ENABLED = BuildConfig.DEBUG // Disabled in release for privacy and security

    private var logDir: File? = null
    private var logger: Logger = AndroidLogger()
    private val executor = Executors.newSingleThreadExecutor()
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

    fun w(message: String) {
        logger.w(TAG, message)
        logToFile("W", message)
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
                    if (oldFile.exists()) oldFile.delete()
                    logFile.renameTo(oldFile)
                }

                val timestamp = dateFormat.format(Instant.now())
                val entry = "$timestamp $level/$TAG: $message\n"

                FileWriter(logFile, true).use { writer ->
                    writer.write(entry)
                }
            } catch (_: Exception) {
                // Fail silently for file logging to avoid recursive errors
            }
        }
    }
}
