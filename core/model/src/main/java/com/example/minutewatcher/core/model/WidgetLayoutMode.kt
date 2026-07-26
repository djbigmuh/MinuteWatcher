package com.example.minutewatcher.core.model

enum class WidgetLayoutMode(val value: Int) {
    SINGLE_LINE(0),
    TWO_LINES(1),
    THREE_LINES(2),
    SIX_LINES(3);

    companion object {
        fun fromValue(value: Int): WidgetLayoutMode = entries.find { it.value == value } ?: THREE_LINES
    }
}
