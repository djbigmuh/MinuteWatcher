package com.example.minutewatcher.core.common.util

import android.util.Log
import com.example.minutewatcher.core.common.BuildConfig

/**
 * Централизованное логирование.
 */
object AppLogger {
    private const val TAG = "MinuteWatcher"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun w(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }

    fun e(
        message: String,
        throwable: Throwable? = null,
    ) {
        if (BuildConfig.DEBUG) Log.e(TAG, message, throwable)
    }
}
