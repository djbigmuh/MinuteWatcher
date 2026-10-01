package com.example.minutewatcher.core.common.util

import android.util.Log
import com.example.minutewatcher.core.common.BuildConfig

/**
 * Interface for logging to allow abstraction from android.util.Log for testing.
 */
interface Logger {
    fun d(
        tag: String,
        message: String,
    )

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )
}

/**
 * Logger implementation that delegates to android.util.Log.
 */
class AndroidLogger : Logger {
    override fun d(
        tag: String,
        message: String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                tag,
                message,
            )
        }
    }

    override fun w(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        Log.w(
            tag,
            message,
            throwable,
        )
    }

    override fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        Log.e(
            tag,
            message,
            throwable,
        )
    }
}

/**
 * Logger implementation that does nothing. Used for unit tests.
 */
class NoOpLogger : Logger {
    override fun d(
        tag: String,
        message: String,
    ) {
        // No-op by design.
    }

    override fun w(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        // No-op by design.
    }

    override fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        // No-op by design.
    }
}
