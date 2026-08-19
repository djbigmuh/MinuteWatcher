package com.example.minutewatcher.core.model

private const val VAL_SINGLE_LINE = 0
private const val VAL_TWO_LINES = 1
private const val VAL_THREE_LINES = 2
private const val VAL_SIX_LINES = 3
private const val VAL_SIMPLE = 4

/**
 * Режимы компоновки виджета.
 *
 * Варианты:
 * - SINGLE_LINE (0) — все в одну строку.
 * - TWO_LINES (1) — две строки.
 * - THREE_LINES (2) — три строки.
 * - SIX_LINES (3) — шесть строк.
 * - SIMPLE (4) — упрощённый режим (при превышении лимита).
 *
 * fromValue возвращает THREE_LINES для невалидных значений.
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
