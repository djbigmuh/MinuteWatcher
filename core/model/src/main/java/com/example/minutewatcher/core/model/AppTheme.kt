package com.example.minutewatcher.core.model

import androidx.appcompat.app.AppCompatDelegate

enum class AppTheme(val value: Int) {
    FOLLOW_SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromValue(value: Int): AppTheme {
            return entries.find { it.value == value } ?: FOLLOW_SYSTEM
        }
    }
}
