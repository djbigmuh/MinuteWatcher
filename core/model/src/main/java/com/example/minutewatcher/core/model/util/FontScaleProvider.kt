package com.example.minutewatcher.core.model.util

object FontScaleProvider {
    fun getMultiplier(fontSizeSetting: Int): Float = when (fontSizeSetting) {
        0 -> 0.8f  // -2
        1 -> 0.9f  // -1
        2 -> 1.0f  // normal
        3 -> 1.1f  // +1
        4 -> 1.2f  // +2
        else -> 1.0f
    }
}
