package com.example.minutewatcher

import android.content.Context
import kotlinx.coroutines.CoroutineScope

val Context.applicationScope: CoroutineScope
    get() = (applicationContext as MinuteWatcherApp).applicationScope
