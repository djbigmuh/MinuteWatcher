package com.example.minutewatcher.core.model

private const val VAL_SINGLE_LINE = 0
private const val VAL_TWO_LINES = 1
private const val VAL_THREE_LINES = 2
private const val VAL_SIX_LINES = 3
private const val VAL_SIMPLE = 4

/**
 * Widget layout modes.
 *
 * Options:
 * - SINGLE_LINE (0) — all in one line.
 * - TWO_LINES (1) — two lines.
 * - THREE_LINES (2) — three lines.
 * - SIX_LINES (3) — six lines.
 * - SIMPLE (4) — simplified mode (when limit is exceeded).
 *
 * fromValue returns THREE_LINES for invalid values.
 */
enum class WidgetLayoutMode(
    val value: Int,
) {
    SINGLE_LINE(VAL_SINGLE_LINE),
    TWO_LINES(VAL_TWO_LINES),
    THREE_LINES(VAL_THREE_LINES),
    SIX_LINES(VAL_SIX_LINES),
    SIMPLE(VAL_SIMPLE),
    ;

    companion object {
        fun fromValue(value: Int): WidgetLayoutMode = entries.find { it.value == value } ?: THREE_LINES
    }
}
