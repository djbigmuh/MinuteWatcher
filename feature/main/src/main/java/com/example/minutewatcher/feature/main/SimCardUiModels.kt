package com.example.minutewatcher.feature.main

import java.io.Serializable

data class LimitDialogData(
    val slot: Int,
    val name: String,
    val minutes: Int,
    val traffic: Int,
    val rolloverEnabled: Boolean = false,
) : Serializable {
    companion object {
        @Suppress("unused")
        private const val serialVersionUID = 1L
    }
}

data class DayDialogData(
    val slot: Int,
    val day: Int,
) : Serializable {
    companion object {
        @Suppress("unused")
        private const val serialVersionUID = 1L
    }
}

data class AdjustDialogData(
    val slot: Int,
    val currentValue: Float,
    val isMinutes: Boolean,
) : Serializable {
    companion object {
        @Suppress("unused")
        private const val serialVersionUID = 1L
    }
}

data class LanguageOption(
    val code: String?,
    val name: String,
    val flag: String,
)
