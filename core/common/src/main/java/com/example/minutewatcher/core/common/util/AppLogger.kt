package com.example.minutewatcher.core.common.util

import android.util.Log

/**
 * Централизованное логирование.
 */
object AppLogger {
    private const val TAG = "MinuteWatcher"
    
    // Fallback if BuildConfig is not easily accessible across all modules without explicit imports
    private const val IS_DEBUG = true 

    fun d(message: String) {
        if (IS_DEBUG) Log.d(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun e(
        message: String,
        throwable: Throwable? = null,
    ) {
        Log.e(TAG, message, throwable)
    }
}
