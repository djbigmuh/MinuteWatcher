package com.github.djbigmuh.minutewatcher.core.common.util

import java.text.DecimalFormatSymbols
import java.util.Locale

object NumberInputNormalizer {
    /**
     * Normalizes a numeric string input based on CLDR symbols of the specified [locale] into
     * a canonical format suitable for [toDoubleOrNull] or [toIntOrNull].
     *
     * Rules:
     * - Decimal separators set (`decimalChars`): [DecimalFormatSymbols.getDecimalSeparator] and `'\u066B'`.
     *   If neither `'.'` nor `','` is in `decimalChars`, `'.'` is added to `decimalChars`.
     * - Grouping separators set (`groupingChars`): [DecimalFormatSymbols.getGroupingSeparator], `'\u066C'`,
     *   spaces (`' '`, `'\u00A0'`, `'\u202F'`, `'\u2009'`). Also includes `','` if `','` is not in `decimalChars`,
     *   and `'.'` if `'.'` is not in `decimalChars`.
     * - Processing order per character:
     *   1. [Character.isDigit]: appended as base-10 ASCII digit.
     *   2. `decimalChars` check: appended as `'.'`.
     *   3. `groupingChars` check: skipped.
     *   4. Other characters: appended unchanged.
     *
     * Precedence: `decimalChars` check is performed strictly before `groupingChars` check.
     *
     * @param raw The raw numeric input string.
     * @param locale Effective locale for determining CLDR decimal and grouping separators.
     * @return Normalized string containing ASCII digits and canonical '.' decimal separator.
     */
    fun normalize(
        raw: String,
        locale: Locale,
    ): String {
        if (raw.isEmpty()) return ""

        val symbols = DecimalFormatSymbols.getInstance(locale)
        val decimalChars = mutableSetOf(symbols.decimalSeparator, '\u066B')
        if ('.' !in decimalChars && ',' !in decimalChars) {
            decimalChars.add('.')
        }

        val groupingChars =
            mutableSetOf(
                symbols.groupingSeparator,
                '\u066C',
                ' ',
                '\u00A0',
                '\u202F',
                '\u2009',
            )
        if (',' !in decimalChars) {
            groupingChars.add(',')
        }
        if ('.' !in decimalChars) {
            groupingChars.add('.')
        }

        val sb = StringBuilder()
        for (c in raw) {
            when {
                Character.isDigit(c) -> {
                    val digit = Character.digit(c, 10)
                    if (digit != -1) sb.append(digit)
                }

                c in decimalChars -> {
                    sb.append('.')
                }

                c in groupingChars -> {
                    // Skip grouping separator
                }

                else -> {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    /**
     * Determines whether the given [text] represents a zero numerical value
     * under any supported decimal or grouping separator format.
     * Not intended for arbitrary number parsing.
     *
     * @param text String input to check.
     * @return True if the text evaluates to 0, false if blank, invalid, or non-zero.
     */
    fun isZeroValue(text: String): Boolean {
        if (text.isBlank()) return false
        val value = normalize(text, Locale.ROOT).toFloatOrNull()
        return value == 0f
    }
}
