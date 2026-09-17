package com.example.minutewatcher.core.common.util

object NumberInputNormalizer {
    /**
     * Normalizes a numeric string input from various locales into a format
     * that can be parsed by [toDoubleOrNull] or [toIntOrNull].
     *
     * Handles:
     * - Unicode digits (Arabic-Indic, Persian, etc.) -> ASCII 0-9
     * - Common decimal separators (comma, Arabic decimal separator) -> dot
     * - Removes thousands separators (spaces, Arabic thousands separator)
     *
     * @param raw The raw input string from user.
     * @return Normalized string containing only ASCII digits and potentially one dot.
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""

        val sb = StringBuilder()

        for (c in raw) {
            when {
                // 1. Convert Unicode digits to ASCII
                Character.isDigit(c) -> {
                    val digit = Character.digit(c, 10)
                    if (digit != -1) sb.append(digit)
                }

                // 2. Normalize decimal separators
                c == '.' || c == ',' || c == '\u066B' -> {
                    sb.append('.')
                }

                // 3. Ignore thousands separators and other noise
                c == ' ' || c == '\u00A0' || c == '\u202F' || c == '\u066C' -> {
                    // Skip
                }

                // 4. Keep other characters (they will cause parse fail later, which is correct)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
