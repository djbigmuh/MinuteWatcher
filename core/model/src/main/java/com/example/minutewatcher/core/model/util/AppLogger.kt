package com.example.minutewatcher.core.model.util

import android.util.Log
import com.example.minutewatcher.core.model.BuildConfig

/**
 * Централизованное логирование.
 */
object AppLogger {
    private const val TAG = "MinuteWatcher"
    private val isDebug = BuildConfig.DEBUG

    fun d(message: String) {
        if (isDebug) Log.d(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }
}
