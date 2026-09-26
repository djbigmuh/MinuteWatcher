package com.example.minutewatcher.core.common.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NumberInputNormalizerTest {
    private val arLocale = Locale.forLanguageTag("ar")

    @Test
    fun `test normalize ar digits`() {
        // Arabic-Indic digits: ١٥٫٥ (15.5)
        assertEquals("15.5", NumberInputNormalizer.normalize("\u0661\u0665\u066B\u0665", arLocale))
    }

    @Test
    fun `test normalize fa digits`() {
        // Persian digits with comma as decimal separator in France/Germany/Persian locale
        val frLocale = Locale.FRANCE
        assertEquals("12.5", NumberInputNormalizer.normalize("\u06F1\u06F2,\u06F5", frLocale))
    }

    @Test
    fun `test normalize space separators`() {
        val frLocale = Locale.FRANCE
        assertEquals("1234.56", NumberInputNormalizer.normalize("1 234,56", frLocale))
        assertEquals("1234.56", NumberInputNormalizer.normalize("1\u00A0234,56", frLocale))
        assertEquals("1234.56", NumberInputNormalizer.normalize("1\u202F234,56", frLocale))
    }

    @Test
    fun `test normalize ar separators`() {
        // 1٬234٫56 -> 1234.56
        assertEquals("1234.56", NumberInputNormalizer.normalize("1\u066C234\u066B56", arLocale))
    }

    @Test
    fun `test normalize plain ASCII`() {
        val usLocale = Locale.US
        assertEquals("1.5", NumberInputNormalizer.normalize("1.5", usLocale))
        assertEquals("15", NumberInputNormalizer.normalize("15", usLocale))
        assertEquals("", NumberInputNormalizer.normalize("", usLocale))
        assertEquals("", NumberInputNormalizer.normalize("", Locale.GERMANY))
    }

    @Test
    fun `test normalize Germany locale`() {
        val deLocale = Locale.GERMANY
        assertEquals("1234", NumberInputNormalizer.normalize("1.234", deLocale))
        assertEquals("1.5", NumberInputNormalizer.normalize("1,5", deLocale))
        assertEquals("1234.56", NumberInputNormalizer.normalize("1.234,56", deLocale))
    }

    @Test
    fun `test normalize US locale`() {
        val usLocale = Locale.US
        assertEquals("1234", NumberInputNormalizer.normalize("1,234", usLocale))
        assertEquals("1.5", NumberInputNormalizer.normalize("1.5", usLocale))
        assertEquals("1234.56", NumberInputNormalizer.normalize("1,234.56", usLocale))
    }

    @Test
    fun `test normalize invalid characters`() {
        assertEquals("123.45abc", NumberInputNormalizer.normalize("123.45abc", Locale.US))
    }

    @Test
    fun `test toIntOrNull compatibility`() {
        // ١٢٣ -> 123
        val normalized = NumberInputNormalizer.normalize("\u0661\u0662\u0663", arLocale)
        assertEquals("123", normalized)
        assertEquals(123, normalized.toIntOrNull())
    }
}
