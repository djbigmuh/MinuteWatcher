package com.example.minutewatcher.core.common.util

import android.util.Log
import com.example.minutewatcher.core.common.BuildConfig

/**
 * Interface for logging to allow abstraction from android.util.Log for testing.
 */
interface Logger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Logger implementation that delegates to android.util.Log.
 */
class AndroidLogger : Logger {
    override fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}

/**
 * Logger implementation that does nothing. Used for unit tests.
 */
class NoOpLogger : Logger {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}
