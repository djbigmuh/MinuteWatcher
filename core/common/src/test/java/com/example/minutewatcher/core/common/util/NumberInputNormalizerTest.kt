package com.example.minutewatcher.core.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberInputNormalizerTest {
    @Test
    fun `test normalize ar digits`() {
        // Arabic-Indic digits: ١٥٫٥ (15.5)
        assertEquals("15.5", NumberInputNormalizer.normalize("\u0661\u0665\u066B\u0665"))
    }

    @Test
    fun `test normalize fa digits`() {
        // Persian digits: ۱۲,۵ (12.5)
        assertEquals("12.5", NumberInputNormalizer.normalize("\u06F1\u06F2,\u06F5"))
    }

    @Test
    fun `test normalize space separators`() {
        assertEquals("1234.56", NumberInputNormalizer.normalize("1 234,56"))
        assertEquals("1234.56", NumberInputNormalizer.normalize("1\u00A0234,56"))
        assertEquals("1234.56", NumberInputNormalizer.normalize("1\u202F234,56"))
    }

    @Test
    fun `test normalize ar separators`() {
        // 1٬234٫56
        assertEquals("1234.56", NumberInputNormalizer.normalize("1\u066C234\u066B56"))
    }

    @Test
    fun `test normalize plain ASCII`() {
        assertEquals("1.5", NumberInputNormalizer.normalize("1.5"))
        assertEquals("15", NumberInputNormalizer.normalize("15"))
        assertEquals("", NumberInputNormalizer.normalize(""))
    }

    @Test
    fun `test normalize invalid characters`() {
        assertEquals("123.45abc", NumberInputNormalizer.normalize("123.45abc"))
    }

    @Test
    fun `test toIntOrNull compatibility`() {
        // ١٢٣ -> 123
        val normalized = NumberInputNormalizer.normalize("\u0661\u0662\u0663")
        assertEquals("123", normalized)
        assertEquals(123, normalized.toIntOrNull())
    }
}
