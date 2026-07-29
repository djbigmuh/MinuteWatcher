package com.example.minutewatcher.feature.main

import java.io.Serializable

@Suppress("SerialVersionUIDInSerializableClass")
data class LimitDialogData(
    val slot: Int,
    val name: String,
    val minutes: Int,
    val traffic: Int,
    val rolloverEnabled: Boolean = false,
) : Serializable

@Suppress("SerialVersionUIDInSerializableClass")
data class DayDialogData(
    val slot: Int,
    val day: Int,
) : Serializable

@Suppress("SerialVersionUIDInSerializableClass")
data class AdjustDialogData(
    val slot: Int,
    val currentValue: Float,
    val isMinutes: Boolean,
) : Serializable

data class LanguageOption(
    val code: String?,
    val name: String,
    val flag: String,
)
